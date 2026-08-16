package com.apex.maptool.spine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Đọc skeleton Spine 4.x dạng NHỊ PHÂN ({@code .skel} / {@code .skel.bytes}) → {@link SpineData}.
 *
 * <p>Port 1:1 từ bản gốc Esoteric Software
 * {@code Assets/Spine/Runtime/spine-csharp/SkeletonBinary.cs} (1379 dòng) của client Unity.
 * Mỗi section bên dưới có ghi rõ dòng tương ứng trong file C# đó.
 *
 * <p><b>Nguyên tắc sống còn</b>: định dạng TUẦN TỰ, không có bảng offset. Mọi section đều phải
 * đọc ĐỦ số byte kể cả những phần tool không dùng (IK/Transform/Path/Physics constraint,
 * bounding box / clipping / point / path attachment, deform / drawOrder / event timeline...).
 * Bỏ qua = lệch con trỏ = hỏng toàn bộ phần sau. Vì vậy cuối hàm có kiểm tra
 * "con trỏ phải dừng đúng bằng kích thước file" — đây là cách phát hiện port sai.
 *
 * <p><b>Những gì được điền vào model</b> (model của tool rất hẹp, xem {@link SpineData}):
 * bones (8 field + parentIdx), slots (name/bone/attachment), attachment region + mesh
 * (kể cả linked mesh đã resolve), animation → bone timeline rotate/translate/scale.
 * Mọi thứ khác đọc-rồi-vứt.
 *
 * <p><b>Mesh có weight</b>: giữ NGUYÊN dạng phẳng của JSON {@code [n, boneIdx, vx, vy, w, ...]}
 * vì {@code SpineRenderer.drawMesh} đã hỗ trợ sẵn dạng này (nó phân biệt bằng
 * {@code vertices.length != uvs.length}) — không cần bung ra setup pose.
 *
 * <p><b>Linked mesh</b>: đọc xong toàn bộ skins mới resolve (giống C# dòng 345-356) — copy
 * {@code uvs/triangles/vertices/hull} từ mesh cha, giữ nguyên {@code path} riêng của nó.
 * Nếu không tìm thấy mesh cha thì attachment đó ở lại dạng mesh rỗng (renderer tự bỏ qua)
 * và log CẢNH BÁO đúng 1 lần cho mỗi file.
 */
public final class SpineBinary {

    // ── Hằng số timeline (SkeletonBinary.cs dòng 46-56) ──────────────────────
    private static final int BONE_ROTATE = 0, BONE_TRANSLATE = 1, BONE_TRANSLATEX = 2, BONE_TRANSLATEY = 3,
            BONE_SCALE = 4, BONE_SCALEX = 5, BONE_SCALEY = 6, BONE_SHEAR = 7, BONE_SHEARX = 8, BONE_SHEARY = 9,
            BONE_INHERIT = 10;
    private static final int SLOT_ATTACHMENT = 0, SLOT_RGBA = 1, SLOT_RGB = 2, SLOT_RGBA2 = 3, SLOT_RGB2 = 4,
            SLOT_ALPHA = 5;
    private static final int ATTACHMENT_DEFORM = 0, ATTACHMENT_SEQUENCE = 1;
    private static final int PATH_POSITION = 0, PATH_SPACING = 1, PATH_MIX = 2;
    private static final int PHYSICS_RESET = 8;
    private static final int CURVE_STEPPED = 1, CURVE_BEZIER = 2;

    // ── Trạng thái đọc ──────────────────────────────────────────────────────
    private final Path src;
    private final byte[] b;
    private int p;                       // con trỏ byte hiện tại
    private String[] strings = new String[0];
    private boolean nonessential;
    private float animDur;               // max time của MỌI timeline trong animation đang đọc

    // thống kê (chỉ dùng cho main() tự kiểm)
    private String version = "?";
    private int skinCount, attCount;

    // linked mesh chờ resolve sau khi đọc xong skins
    private final List<Linked> linked = new ArrayList<>();
    private boolean warnedLinked;

    // Tên các skin theo ĐÚNG thứ tự index của file (default skin = "default"; nếu file không có
    // default skin thì nó KHÔNG chiếm index — giống C# 330-343). Dùng để ghi DeformTimeline.skin.
    private final List<String> skinNames = new ArrayList<>();

    private SpineBinary(Path src, byte[] data) {
        this.src = src;
        this.b = data;
    }

    // ════════════════════════════════════════════════════════════════════════
    // API công khai
    // ════════════════════════════════════════════════════════════════════════

    /** Đọc file .skel / .skel.bytes (Spine 4.x) → SpineData. Ném IOException nếu hỏng. */
    public static SpineData load(Path skel) throws IOException {
        byte[] data;
        try {
            data = Files.readAllBytes(skel);
        } catch (IOException e) {
            throw new IOException("[SpineBinary] không đọc được " + skel + ": " + e.getMessage(), e);
        }
        return new SpineBinary(skel, data).parseChecked();
    }

    /** Version đọc được từ header (vd "4.2.43") — để log/cảnh báo. null nếu không đọc nổi. */
    public static String peekVersion(Path skel) {
        try {
            byte[] data = Files.readAllBytes(skel);
            SpineBinary r = new SpineBinary(skel, data);
            r.i64();                       // hash
            String v = r.str();            // version
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Các hàm đọc cơ bản — SkeletonInput (SkeletonBinary.cs dòng 1200-1321)
    // Mọi số nhiều byte đều BIG-ENDIAN (giống Java) → port thẳng, không đảo byte.
    // ════════════════════════════════════════════════════════════════════════

    private void need(int k) throws IOException {
        if (p + k > b.length) throw err("hết dữ liệu (cần thêm " + k + " byte)");
    }

    /** C# {@code Read()} / {@code ReadUByte()}: 0..255. KHÔNG được để âm (flags & 128 sẽ sai). */
    private int u8() throws IOException {
        need(1);
        return b[p++] & 0xFF;
    }

    /** C# {@code ReadSByte()}: byte có dấu. 4.2 không dùng, port cho đủ bộ. */
    @SuppressWarnings("unused")
    private int s8() throws IOException {
        need(1);
        return b[p++];
    }

    /** C# {@code ReadBoolean()}: khác 0 = true. */
    private boolean bool() throws IOException {
        return u8() != 0;
    }

    /** C# {@code ReadInt()}: 4 byte big-endian có dấu (dùng cho màu RGBA8888, modeAndIndex...). */
    private int i32() throws IOException {
        need(4);
        int v = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
        p += 4;
        return v;
    }

    /** C# {@code ReadLong()}: 8 byte big-endian. */
    private long i64() throws IOException {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[p + i] & 0xFF);
        p += 8;
        return v;
    }

    /** C# {@code ReadFloat()}: 4 byte big-endian IEEE-754. */
    private float f32() throws IOException {
        return Float.intBitsToFloat(i32());
    }

    /** varint {@code ReadInt(true)} — dùng cho mọi số lượng / chỉ số / độ dài. */
    private int varint() throws IOException {
        return varint(true);
    }

    /**
     * VARINT 7-bit, tối đa 5 byte, bit 0x80 = "còn byte nữa" (C# dòng 1274-1291).
     * {@code optimizePositive=false} → giải zigzag {@code (r >> 1) ^ -(r & 1)} (chỉ Event.Int dùng).
     * Java int = C# int (32-bit có dấu) nên port 1:1, dùng {@code >>} y hệt C#.
     */
    private int varint(boolean optimizePositive) throws IOException {
        int x = u8();
        int result = x & 0x7F;
        if ((x & 0x80) != 0) {
            x = u8();
            result |= (x & 0x7F) << 7;
            if ((x & 0x80) != 0) {
                x = u8();
                result |= (x & 0x7F) << 14;
                if ((x & 0x80) != 0) {
                    x = u8();
                    result |= (x & 0x7F) << 21;
                    if ((x & 0x80) != 0) result |= (u8() & 0x7F) << 28;
                }
            }
        }
        return optimizePositive ? result : ((result >> 1) ^ -(result & 1));
    }

    /**
     * C# {@code ReadString()}: varint {@code byteCount}; 0 → null, 1 → chuỗi rỗng,
     * ngược lại {@code byteCount-1} byte UTF-8.
     */
    private String str() throws IOException {
        int byteCount = varint();
        if (byteCount == 0) return null;
        if (byteCount == 1) return "";
        byteCount--;
        if (byteCount < 0) throw err("độ dài chuỗi âm");
        need(byteCount);
        String s = new String(b, p, byteCount, StandardCharsets.UTF_8);
        p += byteCount;
        return s;
    }

    /** C# {@code ReadStringRef()}: varint index 1-based vào bảng strings; 0 = null. */
    private String strRef() throws IOException {
        int index = varint();
        if (index == 0) return null;
        if (index - 1 >= strings.length) throw err("stringRef " + index + " vượt bảng strings(" + strings.length + ")");
        return strings[index - 1];
    }

    private IOException err(String msg) {
        String f = src == null ? "?" : String.valueOf(src.getFileName());
        return new IOException("[SpineBinary] " + f + " @byte " + p + "/" + b.length + ": " + msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ReadSkeletonData (SkeletonBinary.cs dòng 130-379)
    // ════════════════════════════════════════════════════════════════════════

    private SpineData parseChecked() throws IOException {
        try {
            return parse();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            IOException io = err("lỗi nội bộ khi parse: " + e);
            io.initCause(e);
            throw io;
        }
    }

    private SpineData parse() throws IOException {
        SpineData d = new SpineData();

        // ── 1. Header (C# 137-159) ──────────────────────────────────────────
        i64();                                  // hash (bỏ)
        String v = str();
        version = v == null ? "" : v;
        // C# 141-142: version > 13 ký tự ⇒ không phải header 4.x (file Spine 3.8 cũ, hoặc không phải .skel).
        if (version.length() > 13)
            throw err("chuỗi version dài " + version.length() + " ký tự → không phải skeleton nhị phân Spine 4.x"
                    + " (file Spine 3.8 cũ, hoặc file JSON/khác bị đưa nhầm vào)");
        if (!version.startsWith("4."))
            throw err("Spine version '" + version + "' không hỗ trợ — bộ đọc này chỉ port định dạng 4.x");
        float hx = f32(), hy = f32(), hw = f32(), hh = f32();
        f32();                                  // referenceScale (bỏ)
        nonessential = bool();
        if (nonessential) {
            f32();                              // fps
            str();                              // imagesPath
            str();                              // audioPath
        }
        // CẢNH BÁO: 76 file game export không kèm nonessential → x=y=w=h=0.
        // skelHeight=0 sẽ làm SpineCharacter.render tính heightPx=0 (không vẽ gì)
        // ⇒ chỉ ghi đè khi đọc được giá trị > 0, còn lại giữ mặc định 100 (giống JSON thiếu size).
        if (hw > 0) d.skelWidth = hw;
        if (hh > 0) d.skelHeight = hh;
        d.skelX = hx;
        d.skelY = hy;

        // ── 2. Bảng strings dùng chung (C# 164-167) ─────────────────────────
        int n = varint();
        if (n < 0 || n > b.length) throw err("số phần tử bảng strings vô lý: " + n);
        strings = new String[n];
        for (int i = 0; i < n; i++) strings[i] = str();

        // ── 3. Bones (C# 169-191) ───────────────────────────────────────────
        n = varint();
        for (int i = 0; i < n; i++) {
            SpineData.Bone bo = new SpineData.Bone();
            bo.name = str();
            bo.parentIdx = (i == 0) ? -1 : varint();   // BẪY: bone 0 KHÔNG đọc parentIndex
            if (bo.parentIdx >= 0 && bo.parentIdx < d.bones.size()) bo.parent = d.bones.get(bo.parentIdx).name;
            bo.rotation = f32();
            bo.x = f32();
            bo.y = f32();
            bo.scaleX = f32();
            bo.scaleY = f32();
            bo.shearX = f32();
            bo.shearY = f32();
            bo.length = f32();
            bo.inherit = varint();                 // Inherit (ordinal) — IK hai xương cần biết
            bool();                                // skinRequired (bỏ)
            if (nonessential) {
                i32();                             // color
                str();                             // icon
                bool();                            // visible
            }
            d.boneIdx.put(bo.name, d.bones.size());
            d.bones.add(bo);
        }

        // ── 4. Slots (C# 193-220) ───────────────────────────────────────────
        n = varint();
        for (int i = 0; i < n; i++) {
            SpineData.Slot s = new SpineData.Slot();
            s.name = str();
            int bi = varint();
            s.bone = (bi >= 0 && bi < d.bones.size()) ? d.bones.get(bi).name : null;
            i32();                                 // color RGBA8888 (bỏ)
            i32();                                 // darkColor — LUÔN đọc 4 byte, -1 = không có (bỏ)
            s.attachment = strRef();
            s.blend = varint();                    // BlendMode (0 normal · 1 additive · 2 multiply · 3 screen)
            if (nonessential) bool();              // visible
            d.slots.add(s);
        }

        // ── 5. IK constraints (C# 222-240) ──────────────────────────────────
        n = varint();
        for (int i = 0; i < n; i++) {
            SpineData.Ik ik = new SpineData.Ik();
            ik.name = str();
            ik.order = varint();
            int nn = varint();
            ik.bones = new int[Math.max(0, nn)];
            for (int ii = 0; ii < nn; ii++) ik.bones[ii] = varint();
            ik.target = varint();
            int flags = u8();
            // bit1 skinRequired · bit2 bendPositive · bit4 compress · bit8 stretch · bit16 uniform
            ik.bendDirection = (flags & 2) != 0 ? 1 : -1;
            ik.compress = (flags & 4) != 0;
            ik.stretch = (flags & 8) != 0;
            ik.uniform = (flags & 16) != 0;
            // BẪY: bit 32 bật mà bit 64 tắt thì KHÔNG đọc float nào, mix = 1. Bit 32 TẮT ⇒ mix giữ
            // mặc định của IkConstraintData là 0 (IkConstraintData.cs:40 khai `float mix` không gán)
            // ⇒ IkConstraint.Update() thoát ngay. KHÔNG được "sửa" thành 1 cho giống bản JSON.
            if ((flags & 32) != 0) ik.mix = ((flags & 64) != 0) ? f32() : 1;
            if ((flags & 128) != 0) ik.softness = f32();
            if (ik.name != null && ik.target >= 0 && ik.bones.length > 0) {
                d.ikIdx.put(ik.name, d.iks.size());
                d.iks.add(ik);
            }
        }

        // ── 6. Transform constraints (C# 242-269) — CÓ 2 BYTE FLAGS ─────────
        n = varint();
        for (int i = 0; i < n; i++) {
            str();
            varint();                              // order
            int nn = varint();
            for (int ii = 0; ii < nn; ii++) varint();
            varint();                              // target
            int flags = u8();                      // byte flags 1: bit 8..128 → 5 float
            if ((flags & 8) != 0) f32();           // offsetRotation
            if ((flags & 16) != 0) f32();          // offsetX
            if ((flags & 32) != 0) f32();          // offsetY
            if ((flags & 64) != 0) f32();          // offsetScaleX
            if ((flags & 128) != 0) f32();         // offsetScaleY
            flags = u8();                          // byte flags 2: bit 1..64 → 7 float
            if ((flags & 1) != 0) f32();           // offsetShearY
            if ((flags & 2) != 0) f32();           // mixRotate
            if ((flags & 4) != 0) f32();           // mixX
            if ((flags & 8) != 0) f32();           // mixY
            if ((flags & 16) != 0) f32();          // mixScaleX
            if ((flags & 32) != 0) f32();          // mixScaleY
            if ((flags & 64) != 0) f32();          // mixShearY
        }

        // ── 7. Path constraints (C# 271-295) ────────────────────────────────
        n = varint();
        for (int i = 0; i < n; i++) {
            str();
            varint();                              // order
            bool();                                // skinRequired (riêng Path tách ra)
            int nn = varint();
            for (int ii = 0; ii < nn; ii++) varint();
            varint();                              // target = chỉ số SLOT (không phải bone)
            int flags = u8();
            if ((flags & 128) != 0) f32();         // offsetRotation
            f32();                                 // position
            f32();                                 // spacing
            f32();                                 // mixRotate
            f32();                                 // mixX
            f32();                                 // mixY
        }

        // ── 8. Physics constraints (C# 297-328) — có 1 byte lẻ 'step' ───────
        n = varint();
        for (int i = 0; i < n; i++) {
            str();
            varint();                              // order
            varint();                              // bone
            int flags = u8();
            if ((flags & 2) != 0) f32();           // x
            if ((flags & 4) != 0) f32();           // y
            if ((flags & 8) != 0) f32();           // rotate
            if ((flags & 16) != 0) f32();          // scaleX
            if ((flags & 32) != 0) f32();          // shearX
            if ((flags & 64) != 0) f32();          // limit
            u8();                                  // step (1 BYTE chen giữa các float)
            f32();                                 // inertia
            f32();                                 // strength
            f32();                                 // damping
            if ((flags & 128) != 0) f32();         // massInverse
            f32();                                 // wind
            f32();                                 // gravity
            flags = u8();                          // byte flags 2
            if ((flags & 128) != 0) f32();         // mix
        }

        // ── 9. Skins (C# 330-343) ───────────────────────────────────────────
        // Default skin đọc TRƯỚC và KHÔNG có tên; sau đó varint số skin còn lại.
        List<Map<Integer, Map<String, SpineData.Attachment>>> skinsAtt = new ArrayList<>();
        Map<Integer, Map<String, SpineData.Attachment>> def = readSkin(d, true);
        if (def != null) skinsAtt.add(def);
        n = varint();
        for (int i = 0; i < n; i++) skinsAtt.add(readSkin(d, false));
        skinCount = skinsAtt.size();

        // ── 10. Linked meshes (C# 345-356) — KHÔNG đọc byte nào ─────────────
        resolveLinked(skinsAtt);

        // ── 11. Events (C# 358-371) ─────────────────────────────────────────
        // BẪY: volume/balance chỉ có khi audioPath != null, VÀ EventTimeline trong
        // animation cũng phụ thuộc cờ này → bắt buộc lưu lại hasAudio từng event.
        n = varint();
        boolean[] evAudio = new boolean[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            str();                                 // name
            varint(false);                         // Int — VARINT ZIGZAG
            f32();                                 // Float
            str();                                 // String
            String audio = str();                  // AudioPath
            evAudio[i] = audio != null;
            if (audio != null) {
                f32();                             // Volume
                f32();                             // Balance
            }
        }

        // ── 12. Animations (C# 373-376) ─────────────────────────────────────
        n = varint();
        for (int i = 0; i < n; i++) {
            String name = str();
            SpineData.Animation an = readAnimation(d, evAudio);
            d.animations.put(name == null ? ("anim" + i) : name, an);
        }

        // ── 13. Chốt hạ: con trỏ phải dừng ĐÚNG cuối file ───────────────────
        if (p != b.length) throw err("parse xong nhưng con trỏ lệch: còn thừa " + (b.length - p) + " byte");
        // Dựng view phẳng d.skin = gộp mọi skin (đúng hành vi bản chưa tách skin). Chạy SAU
        // resolveLinked cũng không sao: linked mesh sửa TẠI CHỖ đối tượng Attachment, không thay map.
        d.finishSkins();
        return d;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ReadSkin (C# 382-426)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Trả về map {@code slotIndex → (attName → Attachment)} của riêng skin này (để resolve linked mesh),
     * đồng thời ghi vào {@code d.skins} THEO TÊN SKIN — giữ tách bạch để người dùng chọn cải trang.
     * View phẳng {@code d.skin} do {@link SpineData#finishSkins} dựng ở cuối {@code load}.
     */
    private Map<Integer, Map<String, SpineData.Attachment>> readSkin(SpineData d, boolean defaultSkin)
            throws IOException {
        int slotCount;
        String skinName;
        if (defaultSkin) {
            slotCount = varint();
            if (slotCount == 0) return null;       // không có default skin → chỉ tốn đúng 1 varint
            skinName = "default";
        } else {
            skinName = str();                      // tên skin (chỉ để gắn vào DeformTimeline.skin)
            if (nonessential) i32();               // màu skin
            int nb = varint();                     // bones của skin
            for (int i = 0; i < nb; i++) varint();
            for (int k = 0; k < 4; k++) {          // ik / transform / path / physics constraints
                int nc = varint();
                for (int i = 0; i < nc; i++) varint();
            }
            slotCount = varint();
        }
        skinNames.add(skinName);
        Map<Integer, Map<String, SpineData.Attachment>> out = new HashMap<>();
        for (int i = 0; i < slotCount; i++) {
            int slotIndex = varint();
            String slotName = (slotIndex >= 0 && slotIndex < d.slots.size()) ? d.slots.get(slotIndex).name : null;
            int nn = varint();
            for (int ii = 0; ii < nn; ii++) {
                String name = strRef();            // khoá của attachment trong skin
                SpineData.Attachment att = readAttachment(d, slotIndex, name);
                attCount++;
                if (att == null || name == null) continue;
                out.computeIfAbsent(slotIndex, k -> new HashMap<>()).put(name, att);
                if (slotName != null) d.addSkinAttachment(skinName, slotName, name, att);
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ReadAttachment (C# 428-596) — byte flags quyết định tất cả
    // flags & 0x7 = type; bit 8 = "có tên riêng"; bit 16..128 nghĩa khác nhau tuỳ loại.
    // ════════════════════════════════════════════════════════════════════════

    private SpineData.Attachment readAttachment(SpineData d, int slotIndex, String attachmentName)
            throws IOException {
        int flags = u8();
        String name = (flags & 8) != 0 ? strRef() : attachmentName;
        int type = flags & 0x7;
        switch (type) {

            case 0: {                              // ── Region (C# 436-466) ──
                String path = (flags & 16) != 0 ? strRef() : null;
                if ((flags & 32) != 0) i32();      // color (bỏ)
                if ((flags & 64) != 0) readSequence();
                float rotation = (flags & 128) != 0 ? f32() : 0;
                float x = f32(), y = f32(), sx = f32(), sy = f32(), w = f32(), h = f32();
                if (path == null) path = name;
                SpineData.Attachment a = new SpineData.Attachment();
                a.type = "region";
                a.path = path;
                a.x = x;
                a.y = y;
                a.rotation = rotation;
                a.scaleX = sx;
                a.scaleY = sy;
                a.width = w;
                a.height = h;
                return a;
            }

            case 1: {                              // ── Boundingbox (C# 467-478) ──
                readVertices((flags & 16) != 0);   // BẪY: boundingbox dùng bit 16 cho weighted
                if (nonessential) i32();
                return null;                       // tool không vẽ
            }

            case 2: {                              // ── Mesh (C# 479-517) ──
                String path = (flags & 16) != 0 ? strRef() : name;
                if ((flags & 32) != 0) i32();      // color (bỏ)
                if ((flags & 64) != 0) readSequence();
                int hullLength = varint();
                Verts vs = readVertices((flags & 128) != 0);   // BẪY: mesh dùng bit 128
                float[] uvs = new float[vs.length];
                for (int i = 0; i < vs.length; i++) uvs[i] = f32();
                // BẪY: (vertices.length - hullLength - 2) * 3, với vertices.length = vertexCount*2
                // còn hullLength là varint đọc thẳng (CHƯA nhân 2).
                int triCount = (vs.length - hullLength - 2) * 3;
                if (triCount < 0) throw err("mesh '" + name + "': số triangle âm (" + triCount
                        + ") — vertices.length=" + vs.length + " hull=" + hullLength);
                int[] tris = new int[triCount];
                // BẪY: 4.2 dùng VARINT cho short array, không phải 2 byte
                for (int i = 0; i < triCount; i++) tris[i] = varint();
                if (nonessential) {
                    int ne = varint();
                    for (int i = 0; i < ne; i++) varint();      // edges
                    f32();                                      // width
                    f32();                                      // height
                }
                SpineData.Attachment a = new SpineData.Attachment();
                a.type = "mesh";
                a.path = path;
                a.uvs = uvs;
                a.triangles = tris;
                a.vertices = vs.flat;
                a.hull = hullLength;
                return a;
            }

            case 3: {                              // ── Linkedmesh (C# 518-545) ──
                String path = (flags & 16) != 0 ? strRef() : name;
                if ((flags & 32) != 0) i32();      // color (bỏ)
                if ((flags & 64) != 0) readSequence();
                // bit 128 = inheritTimelines: KHÔNG đọc byte nào
                int skinIndex = varint();
                String parent = strRef();
                if (nonessential) {
                    f32();                          // width
                    f32();                          // height
                }
                SpineData.Attachment a = new SpineData.Attachment();
                a.type = "mesh";
                a.path = path;
                linked.add(new Linked(a, skinIndex, slotIndex, parent));
                return a;                           // uvs/triangles/vertices điền ở resolveLinked()
            }

            case 4: {                              // ── Path (C# 546-565) ──
                Verts vs = readVertices((flags & 64) != 0);     // BẪY: path dùng bit 64
                int nl = vs.length / 6;                          // lengths = vertexCount/3
                for (int i = 0; i < nl; i++) f32();
                if (nonessential) i32();
                return null;
            }

            case 5: {                              // ── Point (C# 566-579) ──
                f32();                             // rotation
                f32();                             // x
                f32();                             // y
                if (nonessential) i32();
                return null;
            }

            case 6: {                              // ── Clipping (C# 580-593) ──
                varint();                          // endSlotIndex
                readVertices((flags & 16) != 0);
                if (nonessential) i32();
                return null;                       // model không cắt → bỏ qua (đúng chủ ý)
            }

            default:
                // type 7 (Sequence) không có nhánh nào trong SkeletonBinary.cs 4.2 →
                // gặp là chắc chắn đã lệch con trỏ, ném luôn cho dễ debug.
                throw err("AttachmentType không hợp lệ: " + type + " (flags=" + flags + ")");
        }
    }

    /** C# {@code ReadSequence} (598-604) = đúng 4 varint. */
    private void readSequence() throws IOException {
        varint();   // count
        varint();   // start
        varint();   // digits
        varint();   // setupIndex
    }

    /** Kết quả của {@code ReadVertices}: {@code length} = số FLOAT (vertexCount*2), không phải số đỉnh. */
    private static final class Verts {
        int length;
        float[] flat;
    }

    /**
     * C# {@code ReadVertices} (606-631).
     * Không weight → {@code vertexCount*2} float (x,y xen kẽ).
     * Có weight → mỗi đỉnh: {@code varint boneCount} + boneCount × ({@code varint bone} + 3 float x,y,weight).
     * Ở đây dựng lại đúng dạng PHẲNG của JSON {@code [n, bone, vx, vy, w, ...]} mà
     * {@code SpineRenderer.drawMesh} đang hiểu (nó tự tách và cộng theo trọng số).
     */
    private Verts readVertices(boolean weighted) throws IOException {
        int vertexCount = varint();
        if (vertexCount < 0) throw err("vertexCount âm: " + vertexCount);
        Verts v = new Verts();
        v.length = vertexCount << 1;
        if (!weighted) {
            v.flat = new float[v.length];
            for (int i = 0; i < v.length; i++) v.flat[i] = f32();
            return v;
        }
        FloatBuf buf = new FloatBuf();
        for (int i = 0; i < vertexCount; i++) {
            int boneCount = varint();
            if (boneCount < 0) throw err("boneCount âm ở đỉnh " + i);
            buf.add(boneCount);
            for (int ii = 0; ii < boneCount; ii++) {
                buf.add(varint());   // bone index
                buf.add(f32());      // x (trong không gian bone)
                buf.add(f32());      // y
                buf.add(f32());      // weight
            }
        }
        v.flat = buf.toArray();
        return v;
    }

    // ── Linked mesh ─────────────────────────────────────────────────────────

    private static final class Linked {
        final SpineData.Attachment mesh;
        final int skinIndex, slotIndex;
        final String parent;

        Linked(SpineData.Attachment mesh, int skinIndex, int slotIndex, String parent) {
            this.mesh = mesh;
            this.skinIndex = skinIndex;
            this.slotIndex = slotIndex;
            this.parent = parent;
        }
    }

    /**
     * C# 345-356 + {@code MeshAttachment.ParentMesh}: copy {@code uvs/triangles/vertices/hull}
     * từ mesh cha, GIỮ NGUYÊN {@code path} riêng (linked mesh dùng region khác của cùng atlas).
     */
    private void resolveLinked(List<Map<Integer, Map<String, SpineData.Attachment>>> skinsAtt) {
        for (Linked lm : linked) {
            SpineData.Attachment parent = null;
            if (lm.skinIndex >= 0 && lm.skinIndex < skinsAtt.size() && lm.parent != null) {
                Map<Integer, Map<String, SpineData.Attachment>> sk = skinsAtt.get(lm.skinIndex);
                if (sk != null) {
                    Map<String, SpineData.Attachment> bySlot = sk.get(lm.slotIndex);
                    if (bySlot != null) parent = bySlot.get(lm.parent);
                }
            }
            if (parent == null || parent.uvs == null) {
                if (!warnedLinked) {
                    warnedLinked = true;
                    System.err.println("[SpineBinary] " + (src == null ? "?" : src.getFileName())
                            + ": không tìm thấy mesh cha '" + lm.parent + "' cho linked mesh → bỏ qua attachment này");
                }
                continue;
            }
            lm.mesh.uvs = parent.uvs;
            lm.mesh.triangles = parent.triangles;
            lm.mesh.vertices = parent.vertices;
            lm.mesh.hull = parent.hull;
        }
        linked.clear();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ReadAnimation (C# 654-1190) — 9 section nối tiếp, LUÔN có đủ, đúng thứ tự
    // ════════════════════════════════════════════════════════════════════════

    private SpineData.Animation readAnimation(SpineData d, boolean[] evAudio) throws IOException {
        SpineData.Animation an = new SpineData.Animation();
        animDur = 0;
        varint();   // tổng số timeline — chỉ để cấp phát list, VẪN PHẢI ĐỌC

        readSlotTimelines(d, an);               // 1
        readBoneTimelines(d, an);               // 2
        readIkTimelines(d, an);                 // 3
        readTransformTimelines();               // 4
        readPathTimelines();                    // 5
        readPhysicsTimelines();                 // 6
        readAttachmentTimelines(d, an);         // 7
        readDrawOrderTimeline(d, an);           // 8
        readEventTimeline(evAudio);             // 9

        an.duration = animDur;
        return an;
    }

    private void dur(float t) {
        if (t > animDur) animDur = t;
    }

    /**
     * Slot timelines (C# 658-827).
     *
     * <p>{@code SLOT_ATTACHMENT} (đổi ảnh) là BẮT BUỘC: đo trên client có 4 skeleton
     * ({@code gio3}, {@code nhanh12/dust}, {@code water_glow}, {@code quaivat}) mà MỌI slot đều để
     * setup attachment = null rồi bật ảnh bằng timeline này ⇒ bỏ qua là vẽ ra trắng trơn.
     * Mọi timeline màu nay ĐƯỢC GIỮ LẠI vào {@link SpineData.ColorTimeline} (trước đây đọc rồi vứt);
     * màu "dark" của RGBA2/RGB2 vẫn bỏ vì model không có two-color tint.
     */
    private void readSlotTimelines(SpineData d, SpineData.Animation an) throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            int slotIndex = varint();
            String slotName = (slotIndex >= 0 && slotIndex < d.slots.size()) ? d.slots.get(slotIndex).name : null;
            for (int ii = 0, nn = varint(); ii < nn; ii++) {
                int type = u8();
                int frameCount = varint();
                if (type == SLOT_ATTACHMENT) {
                    // BẪY: không có bezierCount, không có byte curve
                    List<SpineData.AttKey> keys = new ArrayList<>(Math.max(0, frameCount));
                    for (int fr = 0; fr < frameCount; fr++) {
                        SpineData.AttKey k = new SpineData.AttKey();
                        k.time = f32();
                        k.name = strRef();
                        dur(k.time);
                        keys.add(k);
                    }
                    if (slotName != null && !keys.isEmpty()) an.slotAtt.put(slotName, keys);
                    continue;
                }
                int nc;                            // số kênh màu, mỗi kênh = 1 BYTE
                switch (type) {
                    case SLOT_RGBA:  nc = 4; break;
                    case SLOT_RGB:   nc = 3; break;
                    case SLOT_RGBA2: nc = 7; break;
                    case SLOT_RGB2:  nc = 6; break;
                    case SLOT_ALPHA: nc = 1; break;
                    default: throw err("slot timeline type lạ: " + type);
                }
                varint();                          // bezierCount (bỏ — Java tự cấp phát)
                SpineData.ColorTimeline ct = readColorTimeline(frameCount, nc, type);
                if (slotName != null) {
                    ct.slot = slotName;
                    an.colors.add(ct);
                }
            }
        }
    }

    /**
     * Mô hình chung của mọi timeline màu: time(float) + nc byte, mỗi frame sau thêm 1 byte curve
     * (BEZIER ⇒ 4 float cho MỖI kênh). SỐ BYTE ĐỌC KHÔNG ĐỔI so với bản cũ, chỉ khác là giữ lại giá trị.
     */
    private SpineData.ColorTimeline readColorTimeline(int frameCount, int nc, int type) throws IOException {
        if (frameCount <= 0) throw err("color timeline frameCount = " + frameCount);
        SpineData.ColorTimeline ct = new SpineData.ColorTimeline();
        ct.type = type;
        float time = f32();
        dur(time);
        int[] ch = new int[nc];
        for (int c = 0; c < nc; c++) ch[c] = u8();
        for (int frame = 0; ; frame++) {
            SpineData.ColorKey k = new SpineData.ColorKey();
            k.time = time;
            setChannels(k, ch, type);
            ct.keys.add(k);
            if (frame == frameCount - 1) break;
            float time2 = f32();
            dur(time2);
            for (int c = 0; c < nc; c++) ch[c] = u8();
            int curve = u8();
            if (curve == CURVE_STEPPED) {
                k.stepped = true;
            } else if (curve == CURVE_BEZIER) {
                float[] bz = new float[nc * 4];
                for (int q = 0; q < nc * 4; q++) bz[q] = f32();
                k.curve = bz;
            }
            time = time2;
        }
        return ct;
    }

    /** Đổ các kênh byte 0..255 vừa đọc vào ColorKey theo đúng loại timeline (kênh không có ⇒ giữ 1). */
    private static void setChannels(SpineData.ColorKey k, int[] ch, int type) {
        switch (type) {
            case SLOT_ALPHA:
                k.a = ch[0] / 255f;
                break;
            case SLOT_RGB: case SLOT_RGB2:                  // dark (3 byte sau của RGB2) bỏ
                k.r = ch[0] / 255f; k.g = ch[1] / 255f; k.b = ch[2] / 255f;
                break;
            default:                                        // SLOT_RGBA, SLOT_RGBA2 (dark bỏ)
                k.r = ch[0] / 255f; k.g = ch[1] / 255f; k.b = ch[2] / 255f; k.a = ch[3] / 255f;
        }
    }

    /** Bone timelines (C# 829-941) — section DUY NHẤT tool thực sự dùng dữ liệu. */
    private void readBoneTimelines(SpineData d, SpineData.Animation an) throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            int boneIndex = varint();
            String boneName = (boneIndex >= 0 && boneIndex < d.bones.size()) ? d.bones.get(boneIndex).name : null;
            for (int ii = 0, nn = varint(); ii < nn; ii++) {
                int type = u8();
                int frameCount = varint();
                if (type == BONE_INHERIT) {
                    // BẪY: không đọc bezierCount, không có curve; mỗi frame = float + 1 byte
                    for (int fr = 0; fr < frameCount; fr++) {
                        dur(f32());
                        u8();
                    }
                    continue;
                }
                varint();                          // bezierCount (bỏ)
                // 2 giá trị: TRANSLATE(1), SCALE(4), SHEAR(7). Còn lại 1 giá trị.
                int nv = (type == BONE_TRANSLATE || type == BONE_SCALE || type == BONE_SHEAR) ? 2 : 1;
                Tl tl = readCurveTimeline(frameCount, nv);
                if (boneName == null) continue;
                SpineData.BoneTimeline bt = an.bones.computeIfAbsent(boneName, k -> new SpineData.BoneTimeline());
                switch (type) {
                    case BONE_ROTATE:     bt.rotate = keys(tl, 0); break;
                    case BONE_TRANSLATE:  bt.tx = keys(tl, 0); bt.ty = keys(tl, 1); break;   // tách x/y
                    case BONE_TRANSLATEX: bt.tx = keys(tl, 0); break;
                    case BONE_TRANSLATEY: bt.ty = keys(tl, 0); break;
                    case BONE_SCALE:      bt.sx = keys(tl, 0); bt.sy = keys(tl, 1); break;   // tách x/y
                    case BONE_SCALEX:     bt.sx = keys(tl, 0); break;
                    case BONE_SCALEY:     bt.sy = keys(tl, 0); break;
                    case BONE_SHEAR:      bt.shx = keys(tl, 0); bt.shy = keys(tl, 1); break;
                    case BONE_SHEARX:     bt.shx = keys(tl, 0); break;
                    case BONE_SHEARY:     bt.shy = keys(tl, 0); break;
                    default:
                        throw err("bone timeline type lạ: " + type);
                }
            }
        }
    }

    /**
     * IK constraint timelines (C# 877-902) — KHÔNG có byte CURVE riêng, stepped/bezier nằm trong flags.
     * Cờ mỗi frame: bit1+bit2 mix · bit4 softness · bit8 bendPositive · bit16 compress ·
     * bit32 stretch · bit64 stepped · bit128 bezier (8 float ĐỌC SAU cờ của frame kế).
     *
     * <p>Bezier trong file là 2 lần {@code SetBezier} (mix rồi softness) = 8 float, xếp ĐÚNG như
     * {@link SpineData.IkKey#curve} mong đợi (4 đầu mix, 4 sau softness) nên bê nguyên.
     */
    private void readIkTimelines(SpineData d, SpineData.Animation an) throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            int index = varint();                  // constraint index
            int frameCount = varint();
            varint();                              // bezierCount
            SpineData.IkTimeline tl = new SpineData.IkTimeline();
            tl.ik = index;

            int flags = u8();                      // CHÚ Ý: flags đọc TRƯỚC time
            float time = f32(); dur(time);
            float mix = (flags & 1) != 0 ? (((flags & 2) != 0) ? f32() : 1) : 0;
            float softness = (flags & 4) != 0 ? f32() : 0;
            for (int frame = 0; ; frame++) {
                SpineData.IkKey k = new SpineData.IkKey();
                k.time = time;
                k.mix = mix;
                k.softness = softness;
                k.bendDirection = (flags & 8) != 0 ? 1 : -1;
                k.compress = (flags & 16) != 0;
                k.stretch = (flags & 32) != 0;
                tl.keys.add(k);
                // ">=" chứ không "==": frameCount=0 (file hỏng) thì vẫn thoát, không treo vòng lặp.
                // Số byte đọc khớp y bản cũ vì C# luôn đọc frame đầu rồi mới xét.
                if (frame >= frameCount - 1) break;

                flags = u8();
                float time2 = f32(); dur(time2);
                float mix2 = (flags & 1) != 0 ? (((flags & 2) != 0) ? f32() : 1) : 0;
                float softness2 = (flags & 4) != 0 ? f32() : 0;
                if ((flags & 64) != 0) {
                    k.stepped = true;
                } else if ((flags & 128) != 0) {
                    float[] cv = new float[8];
                    for (int c = 0; c < 8; c++) cv[c] = f32();   // 2 × SetBezier
                    k.curve = cv;
                }
                time = time2; mix = mix2; softness = softness2;
            }
            if (index >= 0 && index < d.iks.size() && !tl.keys.isEmpty()) an.ikTimelines.add(tl);
        }
    }

    /** Transform constraint timelines — mô hình chuẩn 6 giá trị (bezier = 24 float). */
    private void readTransformTimelines() throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            varint();                              // index
            int frameCount = varint();
            varint();                              // bezierCount
            readCurveTimeline(frameCount, 6);
        }
    }

    /** Path constraint timelines — bezierCount đọc NGAY SAU frameCount, trước khi rẽ nhánh. */
    private void readPathTimelines() throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            varint();                              // index
            for (int ii = 0, nn = varint(); ii < nn; ii++) {
                int type = u8();
                int frameCount = varint();
                varint();                          // bezierCount
                int nv;
                switch (type) {
                    case PATH_POSITION: case PATH_SPACING: nv = 1; break;
                    case PATH_MIX: nv = 3; break;
                    default: throw err("path timeline type lạ: " + type);
                }
                readCurveTimeline(frameCount, nv);
            }
        }
    }

    /** Physics constraint timelines — PHYSICS_RESET không có bezierCount và không có curve. */
    private void readPhysicsTimelines() throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            varint();                              // index - 1
            for (int ii = 0, nn = varint(); ii < nn; ii++) {
                int type = u8();
                int frameCount = varint();
                if (type == PHYSICS_RESET) {
                    for (int fr = 0; fr < frameCount; fr++) dur(f32());
                    continue;
                }
                varint();                          // bezierCount
                readCurveTimeline(frameCount, 1);  // mọi loại còn lại đều 1 giá trị
            }
        }
    }

    /**
     * Attachment timelines: deform / sequence (C# 1085-1152). Vòng 3 cấp skin → slot → attachment.
     *
     * <p>DEFORM nay được GIỮ LẠI vào {@link SpineData.DeformTimeline} ở dạng THÔ đúng như file
     * ({@code offset} + đoạn float thay đổi) — không bung ra mảng đầy đủ lúc parse vì:
     * (1) đỡ tốn bộ nhớ, (2) deform có thể nhắm vào attachment mà model KHÔNG giữ
     * (Path/BoundingBox/Clipping — {@code readAttachment} trả null), lúc đó không tính nổi
     * {@code deformLength}. Bung ra khi pose bằng {@link SpineData.DeformKey#expandInto}.
     *
     * <p>SỐ BYTE ĐỌC KHÔNG ĐỔI so với bản cũ ⇒ con trỏ vẫn dừng đúng EOF.
     */
    private void readAttachmentTimelines(SpineData d, SpineData.Animation an) throws IOException {
        for (int i = 0, n = varint(); i < n; i++) {
            int skinIndex = varint();
            String skinName = (skinIndex >= 0 && skinIndex < skinNames.size()) ? skinNames.get(skinIndex) : null;
            for (int ii = 0, nn = varint(); ii < nn; ii++) {
                int slotIndex = varint();
                String slotName = (slotIndex >= 0 && slotIndex < d.slots.size()) ? d.slots.get(slotIndex).name : null;
                for (int iii = 0, nnn = varint(); iii < nnn; iii++) {
                    String attName = strRef();     // attachment name
                    int type = u8();
                    int frameCount = varint();
                    if (type == ATTACHMENT_DEFORM) {
                        if (frameCount <= 0) throw err("deform timeline frameCount = " + frameCount);
                        varint();                  // bezierCount
                        SpineData.DeformTimeline dt = new SpineData.DeformTimeline();
                        dt.skin = skinName;
                        dt.slot = slotName;
                        dt.attachment = attName;
                        float time = f32();        // time frame 0
                        dur(time);
                        for (int frame = 0; ; frame++) {
                            SpineData.DeformKey k = new SpineData.DeformKey();
                            k.time = time;
                            int end = varint();
                            if (end != 0) {
                                // BẪY: 'end' là SỐ FLOAT phải đọc, 'start' là ô ĐẦU TIÊN được ghi
                                // (C# 1045-1062: end += start; for (v = start; v < end; v++)).
                                if (end < 0 || (long) end * 4L > b.length - p - 1L)
                                    throw err("deform: số float vô lý (" + end + ")");
                                k.offset = varint();   // start (chỉ đọc khi end != 0)
                                float[] vs = new float[end];
                                for (int v = 0; v < end; v++) vs[v] = f32();
                                k.vertices = vs;
                            }
                            dt.keys.add(k);
                            if (frame == frameCount - 1) break;
                            float time2 = f32();   // time2
                            dur(time2);
                            int curve = u8();
                            if (curve == CURVE_STEPPED) {
                                k.stepped = true;
                            } else if (curve == CURVE_BEZIER) {
                                float[] bz = new float[4];
                                for (int q = 0; q < 4; q++) bz[q] = f32();
                                k.curve = bz;      // toạ độ tuyệt đối (time, percent 0..1)
                            }
                            time = time2;
                        }
                        if (slotName != null && attName != null) an.deforms.add(dt);
                    } else if (type == ATTACHMENT_SEQUENCE) {
                        for (int frame = 0; frame < frameCount; frame++) {
                            dur(f32());            // time
                            i32();                 // modeAndIndex (4 BYTE, không phải varint)
                            f32();                 // delay
                        }
                    } else {
                        throw err("attachment timeline type lạ: " + type);
                    }
                }
            }
        }
    }

    /**
     * Draw order timeline (C# 1161-1190). Phần "unchanged" chỉ là LOGIC, không đọc byte —
     * nay giải luôn thành hoán vị đầy đủ qua {@code SpineData.resolveDrawOrder}.
     * Dữ liệu vô lý ⇒ BỎ QUA key đó (không ném) để không làm hỏng file vốn đang parse được.
     */
    private void readDrawOrderTimeline(SpineData d, SpineData.Animation an) throws IOException {
        int drawOrderCount = varint();
        int slotCount = d.slots.size();
        for (int i = 0; i < drawOrderCount; i++) {
            float time = f32();                    // time
            dur(time);
            int offsetCount = varint();
            boolean ok = offsetCount >= 0 && offsetCount <= slotCount;
            int[] si = ok ? new int[offsetCount] : null;
            int[] off = ok ? new int[offsetCount] : null;
            for (int ii = 0; ii < offsetCount; ii++) {
                int a = varint();                  // slotIndex
                int o = varint();                  // offset
                if (ok) { si[ii] = a; off[ii] = o; }
            }
            if (!ok) continue;
            int[] order = SpineData.resolveDrawOrder(si, off, slotCount);
            if (order == null) continue;
            SpineData.DrawOrderKey k = new SpineData.DrawOrderKey();
            k.time = time;
            k.order = order;
            an.drawOrder.add(k);
        }
    }

    /** Event timeline (C# 1196-1217) — SỐ BYTE PHỤ THUỘC vào audioPath đọc ở section events. */
    private void readEventTimeline(boolean[] evAudio) throws IOException {
        int eventCount = varint();
        for (int i = 0; i < eventCount; i++) {
            dur(f32());                            // time
            int ei = varint();                     // chỉ số EventData
            varint(false);                         // intValue — zigzag
            f32();                                 // floatValue
            str();                                 // stringValue
            if (ei >= 0 && ei < evAudio.length && evAudio[ei]) {
                f32();                             // volume
                f32();                             // balance
            }
        }
    }

    // ── Timeline chuẩn N giá trị (C# ReadTimeline 802-842 + SetBezier 781-786) ──

    private static final class Tl {
        final float[] time;
        final float[][] val;                       // [frame][nv]
        final boolean[] stepped;
        final float[][] bez;                       // [frame][nv*4] hoặc null
        final int nv;

        Tl(int frameCount, int nv) {
            this.nv = nv;
            time = new float[frameCount];
            val = new float[frameCount][nv];
            stepped = new boolean[frameCount];
            bez = new float[frameCount][];
        }
    }

    /**
     * Mô hình chung: {@code time + N giá trị}, rồi lặp (frameCount-1) lần
     * {@code time2 + N giá trị + 1 byte curve [+ N*4 float nếu BEZIER]}.
     * BẪY: frameCount == 1 ⇒ KHÔNG có byte curve nào.
     * Bezier ghi 4 float (cx1,cy1,cx2,cy2 — toạ độ TUYỆT ĐỐI) cho MỖI giá trị, theo thứ tự giá trị 0,1,...
     */
    private Tl readCurveTimeline(int frameCount, int nv) throws IOException {
        if (frameCount <= 0) throw err("timeline frameCount = " + frameCount + " (phải >= 1)");
        Tl t = new Tl(frameCount, nv);
        float time = f32();
        float[] val = new float[nv];
        for (int v = 0; v < nv; v++) val[v] = f32();
        for (int frame = 0; ; frame++) {
            t.time[frame] = time;
            System.arraycopy(val, 0, t.val[frame], 0, nv);
            dur(time);
            if (frame == frameCount - 1) break;
            float time2 = f32();
            float[] val2 = new float[nv];
            for (int v = 0; v < nv; v++) val2[v] = f32();
            int curve = u8();
            if (curve == CURVE_STEPPED) {
                t.stepped[frame] = true;
            } else if (curve == CURVE_BEZIER) {
                float[] bz = new float[nv * 4];
                for (int k = 0; k < nv * 4; k++) bz[k] = f32();
                t.bez[frame] = bz;
            }
            time = time2;
            val = val2;
        }
        return t;
    }

    /** Đổ 1 "cột" giá trị của timeline sang danh sách Key của model (curve tuyệt đối, giống JSON). */
    private static List<SpineData.Key> keys(Tl t, int vi) {
        List<SpineData.Key> out = new ArrayList<>(t.time.length);
        for (int f = 0; f < t.time.length; f++) {
            SpineData.Key k = new SpineData.Key();
            k.time = t.time[f];
            k.value = t.val[f][vi];
            k.stepped = t.stepped[f];
            float[] bz = t.bez[f];
            if (bz != null) k.curve = new float[]{bz[vi * 4], bz[vi * 4 + 1], bz[vi * 4 + 2], bz[vi * 4 + 3]};
            out.add(k);
        }
        return out;
    }

    /** Bộ đệm float tự tăng trưởng (dùng khi đọc vertices có weight — không biết trước độ dài). */
    private static final class FloatBuf {
        private float[] a = new float[64];
        private int n;

        void add(float f) {
            if (n == a.length) a = Arrays.copyOf(a, n << 1);
            a[n++] = f;
        }

        float[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tự kiểm: java -cp target/classes com.apex.maptool.spine.SpineBinary <thư-mục-hoặc-file>
    // ════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Dùng: java -cp target/classes com.apex.maptool.spine.SpineBinary <thư-mục|file>");
            return;
        }
        Path root = Path.of(args[0]);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (java.util.stream.Stream<Path> st = Files.walk(root)) {
                st.filter(Files::isRegularFile)
                        .filter(f -> {
                            String n = f.getFileName().toString().toLowerCase();
                            return n.endsWith(".skel.bytes") || n.endsWith(".skel");
                        })
                        .sorted()
                        .forEach(files::add);
            }
        } else {
            files.add(root);
        }
        int ok = 0, fail = 0;
        for (Path f : files) {
            SpineBinary r = new SpineBinary(f, Files.readAllBytes(f));
            try {
                SpineData d = r.parseChecked();
                List<String> names = new ArrayList<>(d.animations.keySet());
                java.util.Collections.sort(names);
                System.out.println("[OK] " + f + " version=" + r.version
                        + " bones=" + d.bones.size()
                        + " slots=" + d.slots.size()
                        + " anims=" + names
                        + " skins=" + r.skinCount
                        + " attachments=" + r.attCount);
                ok++;
            } catch (Exception e) {
                System.out.println("[FAIL] " + f + ": " + e.getMessage());
                fail++;
            }
        }
        System.out.println("=== " + ok + " OK / " + fail + " FAIL / " + files.size() + " tổng ===");
    }
}
