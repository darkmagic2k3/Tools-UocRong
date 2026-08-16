package com.apex.maptool.spine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tính world transform mỗi bone (setup pose + apply animation tại time t).
 * Spine matrix [a b; c d] + worldX/worldY. Y-up. Bỏ IK/constraint (FK thuần — đủ cho đa số idle).
 *
 * <h2>CHỐNG ĐÂM DỮ LIỆU (đọc trước khi sửa)</h2>
 * {@link SpineData} là dữ liệu SETUP <b>dùng chung</b>: một {@code SpineCharacter} (⇒ một
 * {@code SpineData} + một {@code SpineSkeleton} + một {@code SpineRenderer}) được cache TĨNH theo
 * thư mục skeleton nên 12 cây dừa của Map13 xài chung đúng 1 bộ. Vì vậy MỌI kết quả pose —
 * kể cả deform / màu slot / thứ tự vẽ — đều ghi vào <b>buffer riêng của skeleton này</b>
 * ({@link #dfm}, {@link #slotA}, {@link #order}), <b>TUYỆT ĐỐI không ghi vào</b>
 * {@code SpineData.Attachment.vertices}. Ghi vào đó là hỏng vĩnh viễn: deform của mesh có weight là
 * ĐỘ LỆCH nên frame sau cộng dồn lên frame trước ⇒ lưới nổ tung; mesh không weight thì mất luôn toạ
 * độ setup; tệ hơn nữa {@code SpineBinary.resolveLinked()} cho linked mesh DÙNG CHUNG tham chiếu
 * {@code float[] vertices} với mesh cha nên một attachment méo là kéo theo cả họ hàng, và
 * {@code MapLayoutCanvas.spineBox()} (cache hộp bao, dùng để cull + chọn chuột) cũng sai theo.
 *
 * <p>Buffer của skeleton vẫn là <b>scratch dùng chung</b>, an toàn ĐÚNG BẰNG mức mảng
 * {@link #slotAtt} đang an toàn hôm nay, nhờ 2 bất biến:
 * <ol>
 *   <li>{@code SpineCharacter.render()} gói <b>pose → draw liền một mạch, đồng bộ</b> trên EDT ⇒
 *       node sau pose đè lên node trước thì node trước đã vẽ xong rồi. Không được tách pose() ra
 *       gọi trước cho cả loạt node rồi mới vẽ, và không được vẽ Spine trên luồng nền.</li>
 *   <li>Mỗi {@link #pose} <b>đặt lại TOÀN BỘ</b> buffer trước khi điền ({@code dfmOn} về false,
 *       màu về 1, {@code order} về 0..n-1) ⇒ không sót giá trị của lượt trước.
 *       {@code pose(null, 0f)} (setup pose) cho ra trạng thái Y HỆT bản chưa có deform.</li>
 * </ol>
 * Mảng deform cấp phát <b>một lần theo slot</b> rồi tái dùng ⇒ vòng lặp vẽ 30fps không sinh rác.
 */
public final class SpineSkeleton {

    public final SpineData data;
    // per-bone world: a,b,c,d, worldX, worldY
    public final float[] a, b, c, d, wx, wy;
    /**
     * Attachment ĐANG gắn của từng slot (cùng thứ tự {@code data.slots}) sau lần {@link #pose} gần
     * nhất — setup attachment, hoặc tên do slot attachment timeline đặt tại thời điểm t.
     * {@code null} = slot rỗng. Renderer đọc mảng này thay vì {@code slot.attachment}.
     */
    public final String[] slotAtt;

    // ── buffer kết quả pose (scratch, xem javadoc lớp) ──────────────────────
    /** tên slot → chỉ số slot (deform/màu trong model khoá theo TÊN). */
    private final Map<String, Integer> slotIdxByName = new HashMap<>();
    /** chỉ số bone của từng slot (-1 = không thấy) — tránh tra HashMap mỗi frame khi vẽ mesh. */
    private final int[] slotBone;
    /** deform[si] = mảng đã nội suy của slot si (dài {@code att.deformLength()}), cấp phát 1 lần. */
    private final float[][] dfm;
    /** buffer phụ giữ key SAU khi nội suy 2 key (cùng độ dài với {@link #dfm}) — cũng tái dùng. */
    private final float[][] dfmTmp;
    /** dfmOn[si] = pose gần nhất CÓ deform cho slot si hay không (reset false đầu mỗi pose). */
    private final boolean[] dfmOn;
    /** màu slot sau pose (setup = 1,1,1,1 vì model không giữ màu setup của slot). */
    private final float[] slotR, slotG, slotB, slotA;
    /** pose trước có đụng vào màu không — chỉ khi đó mới phải trả màu về 1. */
    private boolean colorDirty;
    /** order[i] = chỉ số slot được vẽ ở lượt thứ i. */
    private final int[] order;
    /** pose trước có đổi thứ tự vẽ không — chỉ khi đó mới phải dựng lại 0..n-1. */
    private boolean orderDirty;

    // ── LOCAL ĐÃ ÁP (applied) ────────────────────────────────────────────────
    /**
     * Tương đương {@code ax/ay/arotation/ascaleX/ascaleY/ashearX/ashearY} của {@code Bone.cs}:
     * pose local SAU animation nhưng TRƯỚC constraint. IK đọc và ghi đè mấy mảng này rồi tính
     * lại world, nên KHÔNG thể gộp world vào một vòng như bản chỉ-FK trước đây.
     */
    private final float[] ax, ay, arot, asx, asy, ashx, ashy;

    /** Con trực tiếp của từng bone — chỉ dùng cho {@code SortReset} lúc dựng {@link #cache}. */
    private final int[][] childOf;

    /**
     * Thứ tự cập nhật ({@code Skeleton.updateCache}): phần tử {@code ≥0} là chỉ số BONE,
     * {@code <0} là {@code -(chỉ số IK + 1)}. Không có IK ⇒ đúng {@code 0..n-1}, tức là
     * y hệt vòng lặp của bản chỉ-FK ⇒ map/hiệu ứng không lệch một pixel.
     */
    private final int[] cache;

    /** Tham số IK sau pose (setup, rồi timeline đè lên). Đặt lại đầu mỗi {@link #pose}. */
    private final float[] ikMix, ikSoft;
    private final int[] ikBend;
    private final boolean[] ikComp, ikStr;

    // ── CHỈNH TAY (Player Viewer) — mặc định TẮT ────────────────────────────
    /**
     * Delta local do người dùng kéo tay, đánh theo CHỈ SỐ bone. {@code null} = không chỉnh gì ⇒
     * pose ra kết quả y hệt bản chưa có tính năng này (map/hiệu ứng không lệch một pixel).
     * Mỗi phần tử {@code {Δrotation, Δx, Δy, ×scaleX, ×scaleY}} — cộng/nhân đúng như
     * {@code node-renderer/render.js} làm với {@code boneOverrides}.
     */
    private float[][] boneDelta;

    /** Slot bị ẩn tay. {@code null} = không ẩn slot nào. */
    private boolean[] slotHidden;

    /**
     * Đặt delta bone theo TÊN (rỗng/null ⇒ xoá hết). Chỉ {@code SpineSkeleton} của riêng người gọi
     * mới nên dùng — {@code SpineCharacter} lấy từ cache dùng chung (vd {@code MapLayoutCanvas
     * .SPINE_CACHE}) mà đặt delta là 12 cây dừa cùng méo theo.
     */
    public void setBoneDeltas(Map<String, float[]> byName) {
        if (byName == null || byName.isEmpty()) { boneDelta = null; return; }
        float[][] arr = new float[data.bones.size()][];
        boolean any = false;
        for (Map.Entry<String, float[]> e : byName.entrySet()) {
            Integer bi = data.boneIdx.get(e.getKey());
            float[] v = e.getValue();
            if (bi == null || v == null || v.length < 5) continue;
            arr[bi] = v;
            any = true;
        }
        boneDelta = any ? arr : null;
    }

    /**
     * Ẩn các slot có tên trong {@code names} (rỗng/null ⇒ hiện hết). Cách ẩn bám
     * {@code render.js}: đặt alpha slot = 0 — renderer bỏ qua slot alpha ≤ 0.004 nên không tốn gì.
     */
    public void setHiddenSlots(java.util.Set<String> names) {
        if (names == null || names.isEmpty()) { slotHidden = null; return; }
        boolean[] h = new boolean[data.slots.size()];
        boolean any = false;
        for (String n : names) {
            Integer si = slotIdxByName.get(n);
            if (si != null) { h[si] = true; any = true; }
        }
        slotHidden = any ? h : null;
    }

    public SpineSkeleton(SpineData data) {
        this.data = data;
        int n = data.bones.size();
        a = new float[n]; b = new float[n]; c = new float[n]; d = new float[n];
        wx = new float[n]; wy = new float[n];
        ax = new float[n]; ay = new float[n]; arot = new float[n];
        asx = new float[n]; asy = new float[n]; ashx = new float[n]; ashy = new float[n];
        int nik = data.iks.size();
        ikMix = new float[nik]; ikSoft = new float[nik];
        ikBend = new int[nik]; ikComp = new boolean[nik]; ikStr = new boolean[nik];
        childOf = buildChildren(data);
        cache = buildCache(data, childOf);
        int ns = data.slots.size();
        slotAtt = new String[ns];
        slotBone = new int[ns];
        dfm = new float[ns][];
        dfmTmp = new float[ns][];
        dfmOn = new boolean[ns];
        slotR = new float[ns]; slotG = new float[ns]; slotB = new float[ns]; slotA = new float[ns];
        order = new int[ns];
        for (int i = 0; i < ns; i++) {
            SpineData.Slot s = data.slots.get(i);
            slotAtt[i] = s.attachment;
            slotIdxByName.put(s.name, i);
            Integer bi = data.boneIdx.get(s.bone);
            slotBone[i] = (bi == null) ? -1 : bi;
            slotR[i] = 1; slotG[i] = 1; slotB[i] = 1; slotA[i] = 1;
            order[i] = i;
        }
    }

    /**
     * Pose tại animation `anim` time t (giây). anim=null → setup pose.
     *
     * <p>Thứ tự BẮT BUỘC: slot attachment trước (deform phải biết attachment nào đang gắn để chọn
     * đúng timeline — giống {@code DeformTimeline.Apply} kiểm {@code slot.attachment} trước khi ghi),
     * rồi bone, rồi deform / màu / thứ tự vẽ. Chữ ký GIỮ NGUYÊN 100% (canvas cũ + mới đều gọi).
     */
    public void pose(SpineData.Animation anim, float t) {
        poseSlots(anim, t);
        poseBones(anim, t);
        poseDeform(anim, t);
        poseColors(anim, t);
        poseDrawOrder(anim, t);
        // Ẩn slot phải nằm SAU poseColors, nếu không lần reset màu ngay sau đó xoá mất alpha 0.
        if (slotHidden != null) {
            for (int i = 0; i < slotHidden.length && i < slotA.length; i++) {
                if (slotHidden[i]) { slotA[i] = 0f; colorDirty = true; }
            }
        }
    }

    /**
     * Ba bước của {@code Skeleton.UpdateWorldTransform}: lấy pose local đã áp animation, lấy tham
     * số IK tại thời điểm t, rồi chạy {@link #cache} — bone thì tính world, IK thì kéo xương.
     */
    private void poseBones(SpineData.Animation anim, float t) {
        applyLocal(anim, t);
        // Delta người dùng cộng vào pose local NGAY SAU animation và TRƯỚC constraint — đúng chỗ
        // render.js đặt boneOverrides, nên IK vẫn "nhìn thấy" xương đã bị kéo và giải lại theo.
        if (boneDelta != null) {
            for (int i = 0; i < boneDelta.length && i < arot.length; i++) {
                float[] d = boneDelta[i];
                if (d == null) continue;
                arot[i] += d[0]; ax[i] += d[1]; ay[i] += d[2];
                asx[i] *= d[3]; asy[i] *= d[4];
            }
        }
        poseIkParams(anim, t);
        for (int k = 0; k < cache.length; k++) {
            int e = cache[k];
            if (e >= 0) updateBone(e, ax[e], ay[e], arot[e], asx[e], asy[e], ashx[e], ashy[e]);
            else applyIk(-e - 1);
        }
    }

    /** Pose local = setup + timeline (model không có shear timeline nên shear giữ nguyên setup). */
    private void applyLocal(SpineData.Animation anim, float t) {
        List<SpineData.Bone> bones = data.bones;
        for (int i = 0; i < bones.size(); i++) {
            SpineData.Bone bo = bones.get(i);
            float rot = bo.rotation, x = bo.x, y = bo.y, sx = bo.scaleX, sy = bo.scaleY;
            float shx = bo.shearX, shy = bo.shearY;
            if (anim != null) {
                SpineData.BoneTimeline tl = anim.bones.get(bo.name);
                if (tl != null) {
                    rot += before(tl.rotate, t, 0);
                    x += before(tl.tx, t, 0);
                    y += before(tl.ty, t, 0);
                    sx *= before(tl.sx, t, 1);
                    sy *= before(tl.sy, t, 1);
                    shx += before(tl.shx, t, 0);   // ShearTimeline cộng vào setup (như rotate)
                    shy += before(tl.shy, t, 0);
                }
            }
            ax[i] = x; ay[i] = y; arot[i] = rot; asx[i] = sx; asy[i] = sy;
            ashx[i] = shx; ashy[i] = shy;
        }
    }

    /**
     * World transform của MỘT bone từ local cho trước — bản port {@code Bone.UpdateWorldTransform}
     * ({@code Bone.cs:172-282}) với {@code skeleton.scaleX = scaleY = 1} (tool không dùng scale
     * mức skeleton) nên các phép nhân cuối là nhân 1, đã lược.
     *
     * <p>Ghi lại {@code ax…ashy} y như bản C# — IK gọi hàm này với góc đã sửa nên trạng thái
     * "applied" phải đi theo, không thì IK thứ hai trên cùng chuỗi xương sẽ đọc phải góc cũ.
     */
    private void updateBone(int i, float x, float y, float rotation, float sx, float sy, float shx, float shy) {
        ax[i] = x; ay[i] = y; arot[i] = rotation; asx[i] = sx; asy[i] = sy; ashx[i] = shx; ashy[i] = shy;

        int p = data.bones.get(i).parentIdx;
        float la, lb, lc, ld;
        if (p < 0) {                                   // bone gốc
            la = cos(rotation + shx) * sx;
            lb = cos(rotation + 90 + shy) * sy;
            lc = sin(rotation + shx) * sx;
            ld = sin(rotation + 90 + shy) * sy;
            a[i] = la; b[i] = lb; c[i] = lc; d[i] = ld;
            wx[i] = x; wy[i] = y;
            return;
        }
        float pa = a[p], pb = b[p], pc = c[p], pd = d[p];
        wx[i] = pa * x + pb * y + wx[p];
        wy[i] = pc * x + pd * y + wy[p];

        switch (data.bones.get(i).inherit) {
            case SpineData.INHERIT_ONLY_TRANSLATION:
                a[i] = cos(rotation + shx) * sx;
                b[i] = cos(rotation + 90 + shy) * sy;
                c[i] = sin(rotation + shx) * sx;
                d[i] = sin(rotation + 90 + shy) * sy;
                return;

            case SpineData.INHERIT_NO_ROTATION_OR_REFLECTION: {
                float s = pa * pa + pc * pc, prx;
                if (s > 0.0001f) {
                    s = Math.abs(pa * pd - pb * pc) / s;
                    pb = pc * s;
                    pd = pa * s;
                    prx = (float) Math.toDegrees(Math.atan2(pc, pa));
                } else {
                    pa = 0; pc = 0;
                    prx = 90 - (float) Math.toDegrees(Math.atan2(pd, pb));
                }
                la = cos(rotation + shx - prx) * sx;
                lb = cos(rotation + shy - prx + 90) * sy;
                lc = sin(rotation + shx - prx) * sx;
                ld = sin(rotation + shy - prx + 90) * sy;
                a[i] = pa * la - pb * lc;
                b[i] = pa * lb - pb * ld;
                c[i] = pc * la + pd * lc;
                d[i] = pc * lb + pd * ld;
                return;
            }

            case SpineData.INHERIT_NO_SCALE:
            case SpineData.INHERIT_NO_SCALE_OR_REFLECTION: {
                float rad = rotation * DEG;
                float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
                float za = pa * cs + pb * sn;
                float zc = pc * cs + pd * sn;
                float s = (float) Math.sqrt(za * za + zc * zc);
                if (s > 0.00001f) s = 1 / s;
                za *= s; zc *= s;
                s = (float) Math.sqrt(za * za + zc * zc);
                // skeleton scale = 1 ⇒ vế phải của phép so sánh phản chiếu luôn false
                if (data.bones.get(i).inherit == SpineData.INHERIT_NO_SCALE && (pa * pd - pb * pc < 0)) s = -s;
                float r2 = (float) (Math.PI / 2 + Math.atan2(zc, za));
                float zb = (float) Math.cos(r2) * s;
                float zd = (float) Math.sin(r2) * s;
                la = cos(shx) * sx;
                lb = cos(90 + shy) * sy;
                lc = sin(shx) * sx;
                ld = sin(90 + shy) * sy;
                a[i] = za * la + zb * lc;
                b[i] = za * lb + zb * ld;
                c[i] = zc * la + zd * lc;
                d[i] = zc * lb + zd * ld;
                return;
            }

            default: {                                 // INHERIT_NORMAL — đường của 57176/57178 bone
                la = cos(rotation + shx) * sx;
                lb = cos(rotation + 90 + shy) * sy;
                lc = sin(rotation + shx) * sx;
                ld = sin(rotation + 90 + shy) * sy;
                a[i] = pa * la + pb * lc;
                b[i] = pa * lb + pb * ld;
                c[i] = pc * la + pd * lc;
                d[i] = pc * lb + pd * ld;
            }
        }
    }

    /**
     * Cập nhật {@link #slotAtt} theo slot attachment timeline (giống {@code AttachmentTimeline.Apply}
     * của spine-csharp với blend Setup): {@code t} trước key đầu ⇒ giữ setup attachment, còn lại lấy
     * key CUỐI CÙNG có {@code time <= t}. Timeline này KHÔNG nội suy — nó nhảy bậc.
     */
    private void poseSlots(SpineData.Animation anim, float t) {
        for (int i = 0; i < slotAtt.length; i++) {
            SpineData.Slot s = data.slots.get(i);
            String cur = s.attachment;
            List<SpineData.AttKey> keys = (anim == null) ? null : anim.slotAtt.get(s.name);
            if (keys != null && !keys.isEmpty() && t >= keys.get(0).time) {
                SpineData.AttKey hit = keys.get(0);
                for (int k = 1; k < keys.size() && keys.get(k).time <= t; k++) hit = keys.get(k);
                cur = hit.name;
            }
            slotAtt[i] = cur;
        }
    }

    // ════════════════════ IK CONSTRAINT ═════════════════════════════════════

    /** Con trực tiếp của từng bone, theo thứ tự khai báo (giống {@code Bone.children}). */
    private static int[][] buildChildren(SpineData data) {
        int n = data.bones.size();
        int[] cnt = new int[n];
        for (int i = 0; i < n; i++) {
            int p = data.bones.get(i).parentIdx;
            if (p >= 0 && p < n) cnt[p]++;
        }
        int[][] ch = new int[n][];
        for (int i = 0; i < n; i++) ch[i] = new int[cnt[i]];
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) {
            int p = data.bones.get(i).parentIdx;
            if (p >= 0 && p < n) ch[p][fill[p]++] = i;
        }
        return ch;
    }

    /**
     * Bản port {@code Skeleton.UpdateCache} + {@code SortIkConstraint} + {@code SortBone} +
     * {@code SortReset} ({@code Skeleton.cs:221-420}), rút gọn đúng phần tool có: chỉ IK
     * (không transform/path/physics constraint), mọi bone đều active (không có skin-required).
     *
     * <p><b>KHÔNG có IK ⇒ trả thẳng {@code 0..n-1}</b>: file Spine luôn khai bone cha trước con
     * nên đó chính là thứ tự vòng lặp của bản chỉ-FK ⇒ mọi skeleton map/hiệu ứng render y nguyên.
     */
    private static int[] buildCache(SpineData data, int[][] childOf) {
        int nb = data.bones.size(), nik = data.iks.size();
        if (nik == 0) {
            int[] c = new int[nb];
            for (int i = 0; i < nb; i++) c[i] = i;
            return c;
        }
        boolean[] sorted = new boolean[nb];
        // KHÔNG cấp phát cố định nb+nik: SortReset xoá cờ 'sorted' của cả nhánh con dưới xương gốc
        // của IK, nên những bone đó ĐƯỢC THÊM LẠI (bản gốc cố ý vậy — chúng phải tính lại sau khi
        // IK bẻ góc xương cha). Một bone có thể xuất hiện nhiều lần ⇒ danh sách phải co giãn.
        // Bản cấp phát cứng lặng lẽ bỏ bớt bone khi đầy: đo được 259/926 skeleton pose sai vì thế.
        List<Integer> out = new ArrayList<>(nb + nik * 4);

        // C# duyệt i = 0..constraintCount-1 rồi tìm constraint có order == i. Ở đây chỉ có IK nên
        // sắp theo (order, thứ tự khai báo) là tương đương, mà không phụ thuộc order có liên tục.
        Integer[] byOrder = new Integer[nik];
        for (int i = 0; i < nik; i++) byOrder[i] = i;
        java.util.Arrays.sort(byOrder, (p, q) -> {
            int r = Integer.compare(data.iks.get(p).order, data.iks.get(q).order);
            return r != 0 ? r : Integer.compare(p, q);
        });

        for (Integer ii : byOrder) {
            SpineData.Ik ik = data.iks.get(ii);
            if (ik.target < 0 || ik.target >= nb || ik.bones.length == 0) continue;
            sortBone(ik.target, data, sorted, out);
            int parent = ik.bones[0];
            if (parent < 0 || parent >= nb) continue;
            sortBone(parent, data, sorted, out);
            if (ik.bones.length == 1) {
                out.add(-(ii + 1));
                sortReset(childOf[parent], childOf, sorted);
            } else {
                int child = ik.bones[ik.bones.length - 1];
                if (child < 0 || child >= nb) continue;
                sortBone(child, data, sorted, out);
                out.add(-(ii + 1));
                sortReset(childOf[parent], childOf, sorted);
                sorted[child] = true;
            }
        }
        for (int i = 0; i < nb; i++) sortBone(i, data, sorted, out);

        int[] c = new int[out.size()];
        for (int i = 0; i < c.length; i++) c[i] = out.get(i);
        return c;
    }

    private static void sortBone(int i, SpineData data, boolean[] sorted, List<Integer> out) {
        if (i < 0 || i >= sorted.length || sorted[i]) return;
        int p = data.bones.get(i).parentIdx;
        if (p >= 0) sortBone(p, data, sorted, out);
        sorted[i] = true;
        out.add(i);
    }

    private static void sortReset(int[] kids, int[][] childOf, boolean[] sorted) {
        for (int k : kids) {
            if (k < 0 || k >= sorted.length) continue;
            if (sorted[k]) sortReset(childOf[k], childOf, sorted);
            sorted[k] = false;
        }
    }

    /**
     * Tham số IK tại thời điểm t: mặc định là setup, rồi IK timeline đè lên.
     * {@code mix}/{@code softness} NỘI SUY, còn {@code bendDirection}/{@code compress}/{@code stretch}
     * NHẢY BẬC theo key trước ({@code IkConstraintTimeline.Apply}, nhánh alpha = 1 blend = Setup).
     */
    private void poseIkParams(SpineData.Animation anim, float t) {
        int n = data.iks.size();
        for (int i = 0; i < n; i++) {
            SpineData.Ik ik = data.iks.get(i);
            ikMix[i] = ik.mix; ikSoft[i] = ik.softness;
            ikBend[i] = ik.bendDirection; ikComp[i] = ik.compress; ikStr[i] = ik.stretch;
        }
        if (anim == null || anim.ikTimelines.isEmpty()) return;

        for (int ti = 0; ti < anim.ikTimelines.size(); ti++) {
            SpineData.IkTimeline tl = anim.ikTimelines.get(ti);
            int k = tl.ik;
            if (k < 0 || k >= n || tl.keys.isEmpty()) continue;
            List<SpineData.IkKey> ks = tl.keys;
            if (t < ks.get(0).time) continue;                      // trước key đầu ⇒ giữ setup

            SpineData.IkKey p;
            float mix, soft;
            SpineData.IkKey last = ks.get(ks.size() - 1);
            if (t >= last.time) {
                p = last;
                mix = last.mix; soft = last.softness;
            } else {
                int i2 = 1;
                while (i2 < ks.size() - 1 && ks.get(i2).time <= t) i2++;
                p = ks.get(i2 - 1);
                SpineData.IkKey q = ks.get(i2);
                if (p.stepped || q.time <= p.time) {
                    mix = p.mix; soft = p.softness;
                } else if (p.curve != null && p.curve.length >= 8) {
                    if (p.bez == null) {
                        float[] bz = new float[2 * BEZ];
                        System.arraycopy(bezierPoints(p.time, p.mix, p.curve, 0, q.time, q.mix), 0, bz, 0, BEZ);
                        System.arraycopy(bezierPoints(p.time, p.softness, p.curve, 4, q.time, q.softness), 0, bz, BEZ, BEZ);
                        p.bez = bz;
                    }
                    mix = bezierValue(p.bez, 0, p.time, p.mix, q.time, q.mix, t);
                    soft = bezierValue(p.bez, BEZ, p.time, p.softness, q.time, q.softness, t);
                } else {
                    float f = (t - p.time) / (q.time - p.time);
                    mix = p.mix + (q.mix - p.mix) * f;
                    soft = p.softness + (q.softness - p.softness) * f;
                }
            }
            ikMix[k] = mix; ikSoft[k] = soft;
            ikBend[k] = p.bendDirection; ikComp[k] = p.compress; ikStr[k] = p.stretch;
        }
    }

    /** {@code IkConstraint.Update} — mix = 0 thì thôi luôn (đúng bản gốc, không phải tối ưu). */
    private void applyIk(int k) {
        float mix = ikMix[k];
        if (mix == 0) return;
        SpineData.Ik ik = data.iks.get(k);
        int tgt = ik.target;
        if (tgt < 0 || tgt >= wx.length) return;
        float tx = wx[tgt], ty = wy[tgt];
        if (ik.bones.length == 1) {
            ik1(ik.bones[0], tx, ty, ikComp[k], ikStr[k], ik.uniform, mix);
        } else {
            ik2(ik.bones[0], ik.bones[ik.bones.length - 1], tx, ty,
                    ikBend[k], ikStr[k], ik.uniform, ikSoft[k], mix);
        }
    }

    /** IK MỘT xương ({@code IkConstraint.cs:167-231}). Đo trên client: 25 constraint dùng nhánh này. */
    private void ik1(int i, float targetX, float targetY,
                     boolean compress, boolean stretch, boolean uniform, float alpha) {
        if (i < 0 || i >= wx.length) return;
        int p = data.bones.get(i).parentIdx;
        if (p < 0) return;                          // bản gốc deref bone.parent — bone gốc thì bỏ
        float pa = a[p], pb = b[p], pc = c[p], pd = d[p];
        float rotationIK = -ashx[i] - arot[i];
        float tx, ty;
        int inh = data.bones.get(i).inherit;

        if (inh == SpineData.INHERIT_ONLY_TRANSLATION) {
            tx = targetX - wx[i];
            ty = targetY - wy[i];
        } else {
            if (inh == SpineData.INHERIT_NO_ROTATION_OR_REFLECTION) {
                float s = Math.abs(pa * pd - pb * pc) / Math.max(0.0001f, pa * pa + pc * pc);
                float sa = pa, sc = pc;             // skeleton scale = 1
                pb = -sc * s;
                pd = sa * s;
                rotationIK += (float) Math.toDegrees(Math.atan2(sc, sa));
            }
            float x = targetX - wx[p], y = targetY - wy[p];
            float det = pa * pd - pb * pc;
            if (Math.abs(det) <= 0.0001f) { tx = 0; ty = 0; }
            else {
                tx = (x * pd - y * pb) / det - ax[i];
                ty = (y * pa - x * pc) / det - ay[i];
            }
        }

        rotationIK += (float) Math.toDegrees(Math.atan2(ty, tx));
        if (asx[i] < 0) rotationIK += 180;
        if (rotationIK > 180) rotationIK -= 360;
        else if (rotationIK < -180) rotationIK += 360;

        float sx = asx[i], sy = asy[i];
        if (compress || stretch) {
            if (inh == SpineData.INHERIT_NO_SCALE || inh == SpineData.INHERIT_NO_SCALE_OR_REFLECTION) {
                tx = targetX - wx[i];
                ty = targetY - wy[i];
            }
            float bl = data.bones.get(i).length * sx;
            if (bl > 0.0001f) {
                float dd = tx * tx + ty * ty;
                if ((compress && dd < bl * bl) || (stretch && dd > bl * bl)) {
                    float s = ((float) Math.sqrt(dd) / bl - 1) * alpha + 1;
                    sx *= s;
                    if (uniform) sy *= s;
                }
            }
        }
        updateBone(i, ax[i], ay[i], arot[i] + rotationIK * alpha, sx, sy, ashx[i], ashy[i]);
    }

    /**
     * IK HAI xương ({@code IkConstraint.cs:235-384}) — nhánh chính của game: 1564/1589 constraint,
     * trong đó 1441 có {@code softness != 0} và 1423 bật {@code stretch}, nên không cắt bớt được
     * nhánh nào. Tên biến bám sát bản C# để đối chiếu; {@code done} thay cho {@code goto break_outer}.
     */
    private void ik2(int parent, int child, float targetX, float targetY,
                     int bendDir, boolean stretch, boolean uniform, float softness, float alpha) {
        int nb = wx.length;
        if (parent < 0 || parent >= nb || child < 0 || child >= nb) return;
        if (data.bones.get(parent).inherit != SpineData.INHERIT_NORMAL
                || data.bones.get(child).inherit != SpineData.INHERIT_NORMAL) return;

        float px = ax[parent], py = ay[parent];
        float psx = asx[parent], psy = asy[parent], sx = psx, sy = psy, csx = asx[child];
        int os1, os2, s2;
        if (psx < 0) { psx = -psx; os1 = 180; s2 = -1; } else { os1 = 0; s2 = 1; }
        if (psy < 0) { psy = -psy; s2 = -s2; }
        if (csx < 0) { csx = -csx; os2 = 180; } else os2 = 0;

        float cx = ax[child], cy, cwx, cwy;
        float mA = a[parent], mB = b[parent], mC = c[parent], mD = d[parent];
        boolean u = Math.abs(psx - psy) <= 0.0001f;
        if (!u || stretch) {
            cy = 0;
            cwx = mA * cx + wx[parent];
            cwy = mC * cx + wy[parent];
        } else {
            cy = ay[child];
            cwx = mA * cx + mB * cy + wx[parent];
            cwy = mC * cx + mD * cy + wy[parent];
        }

        int pp = data.bones.get(parent).parentIdx;
        if (pp < 0) return;                         // bản gốc deref parent.parent
        mA = a[pp]; mB = b[pp]; mC = c[pp]; mD = d[pp];
        float id = mA * mD - mB * mC, x = cwx - wx[pp], y = cwy - wy[pp];
        id = Math.abs(id) <= 0.0001f ? 0 : 1 / id;
        float dx = (x * mD - y * mB) * id - px, dy = (y * mA - x * mC) * id - py;
        float l1 = (float) Math.sqrt(dx * dx + dy * dy), l2 = data.bones.get(child).length * csx;
        float a1, a2;

        if (l1 < 0.0001f) {                         // xương cha dài 0 ⇒ tụt về IK một xương
            ik1(parent, targetX, targetY, false, stretch, false, alpha);
            updateBone(child, cx, cy, 0, asx[child], asy[child], ashx[child], ashy[child]);
            return;
        }

        x = targetX - wx[pp];
        y = targetY - wy[pp];
        float tx = (x * mD - y * mB) * id - px, ty = (y * mA - x * mC) * id - py;
        float dd = tx * tx + ty * ty;

        if (softness != 0) {
            softness *= psx * (csx + 1) * 0.5f;
            float td = (float) Math.sqrt(dd), sd = td - l1 - l2 * psx + softness;
            if (sd > 0) {
                float pr = Math.min(1, sd / (softness * 2)) - 1;
                pr = (sd - softness * (1 - pr * pr)) / td;
                tx -= pr * tx;
                ty -= pr * ty;
                dd = tx * tx + ty * ty;
            }
        }

        if (u) {                                    // scale cha đều
            l2 *= psx;
            float cosv = (dd - l1 * l1 - l2 * l2) / (2 * l1 * l2);
            if (cosv < -1) {
                a2 = (float) Math.PI * bendDir;
                cosv = -1;
            } else if (cosv > 1) {
                cosv = 1;
                a2 = 0;
                if (stretch) {
                    float s = ((float) Math.sqrt(dd) / (l1 + l2) - 1) * alpha + 1;
                    sx *= s;
                    if (uniform) sy *= s;
                }
            } else {
                a2 = (float) Math.acos(cosv) * bendDir;
            }
            float ea = l1 + l2 * cosv;
            float eb = l2 * (float) Math.sin(a2);
            a1 = (float) Math.atan2(ty * ea - tx * eb, tx * ea + ty * eb);
        } else {                                    // scale cha không đều ⇒ giải elip
            float qa = psx * l2, qb = psy * l2;
            float aa = qa * qa, bb = qb * qb, ta = (float) Math.atan2(ty, tx);
            float cc = bb * l1 * l1 + aa * dd - aa * bb;
            float c1 = -2 * bb * l1, c2 = bb - aa;
            float disc = c1 * c1 - 4 * c2 * cc;
            a1 = 0; a2 = 0;
            boolean done = false;
            if (disc >= 0) {
                float q = (float) Math.sqrt(disc);
                if (c1 < 0) q = -q;
                q = -(c1 + q) * 0.5f;
                float r0 = q / c2, r1 = cc / q;
                float r = Math.abs(r0) < Math.abs(r1) ? r0 : r1;
                r0 = dd - r * r;
                if (r0 >= 0) {
                    y = (float) Math.sqrt(r0) * bendDir;
                    a1 = ta - (float) Math.atan2(y, r);
                    a2 = (float) Math.atan2(y / psy, (r - l1) / psx);
                    done = true;
                }
            }
            if (!done) {                            // ngoài tầm với ⇒ lấy điểm gần/xa nhất trên elip
                float minAngle = (float) Math.PI, minX = l1 - qa, minDist = minX * minX, minY = 0;
                float maxAngle = 0, maxX = l1 + qa, maxDist = maxX * maxX, maxY = 0;
                float ca = -qa * l1 / (aa - bb);
                if (ca >= -1 && ca <= 1) {
                    ca = (float) Math.acos(ca);
                    x = qa * (float) Math.cos(ca) + l1;
                    y = qb * (float) Math.sin(ca);
                    float dv = x * x + y * y;
                    if (dv < minDist) { minAngle = ca; minDist = dv; minX = x; minY = y; }
                    if (dv > maxDist) { maxAngle = ca; maxDist = dv; maxX = x; maxY = y; }
                }
                if (dd <= (minDist + maxDist) * 0.5f) {
                    a1 = ta - (float) Math.atan2(minY * bendDir, minX);
                    a2 = minAngle * bendDir;
                } else {
                    a1 = ta - (float) Math.atan2(maxY * bendDir, maxX);
                    a2 = maxAngle * bendDir;
                }
            }
        }

        float os = (float) Math.atan2(cy, cx) * s2;
        float rotation = arot[parent];
        a1 = (float) Math.toDegrees(a1 - os) + os1 - rotation;
        if (a1 > 180) a1 -= 360; else if (a1 < -180) a1 += 360;
        updateBone(parent, px, py, rotation + a1 * alpha, sx, sy, 0, 0);

        rotation = arot[child];
        a2 = ((float) Math.toDegrees(a2 + os) - ashx[child]) * s2 + os2 - rotation;
        if (a2 > 180) a2 -= 360; else if (a2 < -180) a2 += 360;
        updateBone(child, cx, cy, rotation + a2 * alpha, asx[child], asy[child], ashx[child], ashy[child]);
    }

    // ════════════════════ DEFORM ════════════════════════════════════════════

    /**
     * Deform ĐÃ nội suy của slot thứ {@code slotIndex} sau {@link #pose} gần nhất.
     * {@code null} = slot này không deform ⇒ người vẽ dùng {@code att.vertices} GỐC.
     *
     * <p>Ý nghĩa mảng trả về (theo {@code VertexAttachment.ComputeWorldVertices}):
     * mesh KHÔNG weight → toạ độ local TUYỆT ĐỐI, <b>THAY THẾ</b> {@code att.vertices};
     * mesh CÓ weight → độ lệch (dx,dy) cộng vào {@code vx,vy} của TỪNG BONE-ENTRY.
     *
     * <p><b>Mảng thuộc sở hữu của skeleton (scratch): người gọi CHỈ ĐƯỢC ĐỌC</b>, và nó chỉ còn
     * đúng cho tới lần {@link #pose} kế tiếp (xem javadoc lớp).
     */
    public float[] deformOf(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= dfmOn.length || !dfmOn[slotIndex]) return null;
        return dfm[slotIndex];
    }

    /**
     * Tính deform của mọi slot tại thời điểm t — bám {@code DeformTimeline.Apply}
     * ({@code Animation.cs:1768-1930}) ở đúng nhánh tool cần: <b>alpha = 1, blend = Setup</b>
     * (chỉ chạy 1 animation, không trộn), tức là:
     * <pre>deform[i] = prev[i] + (next[i] - prev[i]) * percent;</pre>
     * <ul>
     *   <li>{@code t < key[0].time} → <b>KHÔNG deform</b> (C#:1785-1789 {@code deformArray.Clear()}
     *       — về setup pose, KHÔNG phải kẹp về key đầu);</li>
     *   <li>{@code t >= key[last].time} → giữ nguyên key cuối (C#:1822-1839);</li>
     *   <li>{@code percent}: stepped → 0 (giữ key trước), bezier → đường cong (time ↔ percent 0..1)
     *       vì {@code SkeletonBinary.cs:1072} gọi {@code SetBezier(..., value1=0, value2=1)},
     *       còn lại → linear {@code (t - t0)/(t1 - t0)}.</li>
     * </ul>
     * Chỉ áp khi attachment ĐANG gắn trên slot đúng là attachment mà timeline nhắm tới
     * (C#:1773-1774 so {@code TimelineAttachment}) — ở đây so bằng TÊN vì model gộp skin theo tên.
     */
    private void poseDeform(SpineData.Animation anim, float t) {
        boolean any = false;
        for (int i = 0; i < dfmOn.length; i++) if (dfmOn[i]) { any = true; break; }
        if (any) Arrays.fill(dfmOn, false);
        if (anim == null || anim.deforms.isEmpty()) return;

        for (int di = 0; di < anim.deforms.size(); di++) {
            SpineData.DeformTimeline dt = anim.deforms.get(di);
            Integer siO = slotIdxByName.get(dt.slot);
            if (siO == null) continue;
            int si = siO;
            String cur = slotAtt[si];
            if (cur == null || !cur.equals(dt.attachment)) continue;   // attachment khác ⇒ bỏ
            Map<String, SpineData.Attachment> atts = data.skin.get(dt.slot);
            if (atts == null) continue;
            SpineData.Attachment att = atts.get(dt.attachment);
            if (att == null) continue;
            int len = att.deformLength();
            if (len <= 0) continue;                                    // Path/bbox/clip ⇒ model không giữ

            List<SpineData.DeformKey> ks = dt.keys;
            if (ks.isEmpty() || t < ks.get(0).time) continue;          // trước key đầu ⇒ setup

            float[] out = dfm[si];
            if (out == null || out.length != len) { out = new float[len]; dfm[si] = out; }

            SpineData.DeformKey last = ks.get(ks.size() - 1);
            if (t >= last.time) {
                last.expandInto(out, att);
            } else {
                int i = 1;
                while (i < ks.size() - 1 && ks.get(i).time <= t) i++;
                SpineData.DeformKey prev = ks.get(i - 1), next = ks.get(i);
                float percent;
                if (prev.stepped || next.time <= prev.time) percent = 0;
                else if (prev.curve != null && prev.curve.length >= 4) {
                    // Deform là trường hợp DUY NHẤT trục giá trị chạy 0..1 (SkeletonBinary.cs:1072
                    // gọi SetBezier với value1=0, value2=1) — các timeline khác dùng giá trị thật.
                    if (prev.bez == null) {
                        prev.bez = bezierPoints(prev.time, 0f, prev.curve, 0, next.time, 1f);
                    }
                    percent = bezierValue(prev.bez, 0, prev.time, 0f, next.time, 1f, t);
                } else percent = (t - prev.time) / (next.time - prev.time);

                prev.expandInto(out, att);
                if (percent != 0f) {
                    float[] tmp = dfmTmp[si];
                    if (tmp == null || tmp.length != len) { tmp = new float[len]; dfmTmp[si] = tmp; }
                    next.expandInto(tmp, att);
                    for (int j = 0; j < len; j++) out[j] += (tmp[j] - out[j]) * percent;
                }
            }
            dfmOn[si] = true;
        }
    }

    /**
     * World vertex của MỘT mesh trên slot {@code slotIndex} → ghi cặp (x,y) vào {@code out}
     * (dài ≥ {@code 2 × vertexCount}), trả về số đỉnh đã ghi. Đây là bản port của
     * {@code VertexAttachment.ComputeWorldVertices} ({@code VertexAttachment.cs:102-156}), có áp
     * deform của lần {@link #pose} gần nhất:
     * <ul>
     *   <li>mesh KHÔNG weight: deform THAY THẾ {@code att.vertices} (C#:108
     *       {@code if (deformArray.Count > 0) vertices = deformArray.Items;}) rồi
     *       {@code world = (vx,vy) × ma trận bone của slot};</li>
     *   <li>mesh CÓ weight: {@code vx = vertices[b] + deform[f]}, {@code vy = vertices[b+1] + deform[f+1]}
     *       với con trỏ {@code f} chạy <b>2 float mỗi BONE-ENTRY</b> (C#:148), rồi nhân ma trận
     *       từng xương và cộng theo trọng số. Model của tool giữ mesh có weight ở dạng PHẲNG
     *       {@code [n, bone,vx,vy,w, …]} nên phải đếm bone-entry riêng, KHÔNG được tra deform
     *       theo chỉ số đỉnh.</li>
     * </ul>
     * {@code out} do người gọi cấp phát và tái dùng ⇒ không sinh rác mỗi frame.
     */
    public int meshWorldVertices(int slotIndex, SpineData.Attachment att, float[] out) {
        if (att == null || att.uvs == null || att.vertices == null || out == null) return 0;
        int vCount = att.uvs.length / 2;
        if (vCount <= 0 || out.length < vCount * 2) return 0;
        float[] df = deformOf(slotIndex);
        float[] vs = att.vertices;

        if (!att.weighted()) {
            int i = (slotIndex >= 0 && slotIndex < slotBone.length) ? slotBone[slotIndex] : -1;
            if (i < 0) return 0;
            if (df != null && df.length >= vCount * 2) vs = df;         // deform = toạ độ tuyệt đối
            float ba = a[i], bb = b[i], bc = c[i], bd = d[i], bx = wx[i], by = wy[i];
            for (int v = 0; v < vCount; v++) {
                float lx = vs[v * 2], ly = vs[v * 2 + 1];
                out[v * 2] = ba * lx + bb * ly + bx;
                out[v * 2 + 1] = bc * lx + bd * ly + by;
            }
            return vCount;
        }

        int idx = 0, f = 0;
        for (int v = 0; v < vCount; v++) {
            if (idx >= vs.length) return v;
            int n = (int) vs[idx++];
            if (n < 0 || idx + n * 4 > vs.length) return v;
            float sumX = 0, sumY = 0;
            for (int k = 0; k < n; k++) {
                int bone = (int) vs[idx++];
                float vx = vs[idx++], vy = vs[idx++], wt = vs[idx++];
                if (df != null && f + 1 < df.length) { vx += df[f]; vy += df[f + 1]; }
                f += 2;
                if (bone < 0 || bone >= a.length) continue;
                sumX += (a[bone] * vx + b[bone] * vy + wx[bone]) * wt;
                sumY += (c[bone] * vx + d[bone] * vy + wy[bone]) * wt;
            }
            out[v * 2] = sumX;
            out[v * 2 + 1] = sumY;
        }
        return vCount;
    }

    // ════════════════════ MÀU SLOT ══════════════════════════════════════════

    /**
     * Alpha (0..1) của slot sau {@link #pose} — 1 = đục hoàn toàn. Model không giữ màu SETUP của
     * slot (bộ đọc bỏ 4 byte color) nên setup luôn = 1, tức là "không có timeline màu" ⇒ hành vi
     * y hệt bản chưa có màu.
     */
    public float slotAlpha(int slotIndex) {
        return (slotIndex < 0 || slotIndex >= slotA.length) ? 1f : slotA[slotIndex];
    }

    /** Kênh đỏ/lục/lam (0..1) của slot sau {@link #pose}. Đo trên asset thật: 0/325 timeline màu của
     *  hiệu ứng game đổi RGB (chỉ đổi alpha) ⇒ renderer hiện chỉ dùng alpha, giữ 3 hàm này cho đủ. */
    public float slotRed(int slotIndex)   { return (slotIndex < 0 || slotIndex >= slotR.length) ? 1f : slotR[slotIndex]; }
    public float slotGreen(int slotIndex) { return (slotIndex < 0 || slotIndex >= slotG.length) ? 1f : slotG[slotIndex]; }
    public float slotBlue(int slotIndex)  { return (slotIndex < 0 || slotIndex >= slotB.length) ? 1f : slotB[slotIndex]; }

    /**
     * Màu slot tại thời điểm t — bám {@code RGBATimeline.Apply} ({@code Animation.cs:1036-1303})
     * nhánh alpha = 1, blend = Setup: trước key đầu ⇒ màu setup (ở đây là trắng đục), sau key cuối
     * ⇒ giữ key cuối, ở giữa ⇒ nội suy TỪNG KÊNH (mỗi kênh có đường bezier RIÊNG — xem
     * {@code SkeletonBinary.cs:697-706} gọi {@code SetBezier} 4 lần cho r,g,b,a).
     * Timeline loại nào chỉ ghi kênh loại đó lái (RGB không đụng alpha, ALPHA không đụng màu) để
     * 2 timeline trên cùng 1 slot không xoá lẫn nhau.
     */
    private void poseColors(SpineData.Animation anim, float t) {
        if (colorDirty) {
            Arrays.fill(slotR, 1f); Arrays.fill(slotG, 1f); Arrays.fill(slotB, 1f); Arrays.fill(slotA, 1f);
            colorDirty = false;
        }
        if (anim == null || anim.colors.isEmpty()) return;

        for (int ci = 0; ci < anim.colors.size(); ci++) {
            SpineData.ColorTimeline ct = anim.colors.get(ci);
            Integer siO = slotIdxByName.get(ct.slot);
            if (siO == null || ct.keys.isEmpty()) continue;
            int si = siO;
            List<SpineData.ColorKey> ks = ct.keys;
            if (t < ks.get(0).time) continue;                          // trước key đầu ⇒ màu setup

            float r, g, b2, al;
            SpineData.ColorKey last = ks.get(ks.size() - 1);
            if (t >= last.time) {
                r = last.r; g = last.g; b2 = last.b; al = last.a;
            } else {
                int i = 1;
                while (i < ks.size() - 1 && ks.get(i).time <= t) i++;
                SpineData.ColorKey p = ks.get(i - 1), q = ks.get(i);
                r  = chan(ct, p, q, t, p.r, q.r, 0);
                g  = chan(ct, p, q, t, p.g, q.g, 1);
                b2 = chan(ct, p, q, t, p.b, q.b, 2);
                al = chan(ct, p, q, t, p.a, q.a, 3);
            }
            switch (ct.type) {
                case SpineData.SLOT_ALPHA:
                    slotA[si] = clamp01(al);
                    break;
                case SpineData.SLOT_RGB: case SpineData.SLOT_RGB2:
                    slotR[si] = clamp01(r); slotG[si] = clamp01(g); slotB[si] = clamp01(b2);
                    break;
                default:                                               // RGBA / RGBA2
                    slotR[si] = clamp01(r); slotG[si] = clamp01(g); slotB[si] = clamp01(b2);
                    slotA[si] = clamp01(al);
            }
            colorDirty = true;
        }
    }

    /**
     * Nội suy 1 KÊNH màu giữa 2 key (stepped → giữ key trước; bezier riêng từng kênh; còn lại linear).
     *
     * <p>⚠ Bezier của timeline màu chạy trên trục GIÁ TRỊ THẬT của kênh, không phải phần trăm 0..1:
     * {@code SkeletonJson.cs:1291} truyền thẳng {@code value1 = r, value2 = r2} vào {@code SetBezier}.
     * Bản cũ tính ra "phần trăm" rồi mới lerp ⇒ sai đường cong mỗi khi kênh không đi từ 0 tới 1.
     */
    private static float chan(SpineData.ColorTimeline ct, SpineData.ColorKey p, SpineData.ColorKey q,
                              float t, float v0, float v1, int rgbaChannel) {
        if (p.stepped || q.time <= p.time) return v0;
        if (v0 == v1) return v0;                    // kênh đứng yên ⇒ khỏi dò đường cong
        if (p.curve == null) return v0 + (v1 - v0) * ((t - p.time) / (q.time - p.time));
        if (p.bez == null) buildColorBez(ct, p, q);
        if ((p.bezMask & (1 << rgbaChannel)) == 0) {
            return v0 + (v1 - v0) * ((t - p.time) / (q.time - p.time));
        }
        return bezierValue(p.bez, rgbaChannel * BEZ, p.time, v0, q.time, v1, t);
    }

    /** Dựng mẫu bezier cho CẢ 4 kênh của 1 key màu, một lần rồi dùng lại (curveOf cấp phát mảng). */
    private static void buildColorBez(SpineData.ColorTimeline ct, SpineData.ColorKey p, SpineData.ColorKey q) {
        float[] bez = new float[4 * BEZ];
        float[] pv = {p.r, p.g, p.b, p.a}, qv = {q.r, q.g, q.b, q.a};
        int mask = 0;
        for (int ch = 0; ch < 4; ch++) {
            float[] cv = ct.curveOf(p, ch);
            if (cv == null) continue;
            System.arraycopy(bezierPoints(p.time, pv[ch], cv, 0, q.time, qv[ch]), 0, bez, ch * BEZ, BEZ);
            mask |= 1 << ch;
        }
        p.bez = bez;
        p.bezMask = mask;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // ════════════════════ THỨ TỰ VẼ ═════════════════════════════════════════

    /**
     * Thứ tự vẽ slot sau {@link #pose}: {@code drawOrder()[i]} = chỉ số slot được vẽ ở lượt thứ i.
     * Luôn khác null, dài = số slot; không có draw order timeline ⇒ đúng 0..n-1 (y hệt hành vi cũ).
     * <b>Chỉ được ĐỌC</b> (scratch của skeleton).
     */
    public int[] drawOrder() { return order; }

    /**
     * Draw order timeline — NHẢY BẬC, không nội suy ({@code DrawOrderTimeline.Apply}
     * {@code Animation.cs:2014-2065}): lấy key CUỐI CÙNG có {@code time <= t};
     * {@code t} trước key đầu hoặc key có {@code order == null} ⇒ về thứ tự setup.
     */
    private void poseDrawOrder(SpineData.Animation anim, float t) {
        if (orderDirty) {
            for (int i = 0; i < order.length; i++) order[i] = i;
            orderDirty = false;
        }
        if (anim == null || anim.drawOrder.isEmpty()) return;
        List<SpineData.DrawOrderKey> ks = anim.drawOrder;
        if (t < ks.get(0).time) return;
        SpineData.DrawOrderKey hit = ks.get(0);
        for (int i = 1; i < ks.size() && ks.get(i).time <= t; i++) hit = ks.get(i);
        if (hit.order == null || hit.order.length != order.length) return;
        System.arraycopy(hit.order, 0, order, 0, order.length);
        orderDirty = true;
    }

    /**
     * Giá trị timeline bone tại thời điểm {@code t}, hoặc {@code neutral} nếu timeline không có
     * hoặc {@code t} nằm TRƯỚC key đầu tiên.
     *
     * <p>Cái {@code t < key[0].time} này là chỗ dễ sai nhất: {@code RotateTimeline.Apply}
     * ({@code Animation.cs}) ở nhánh {@code MixBlend.Setup} trả bone về ĐÚNG SETUP POSE, chứ
     * không kẹp về key đầu. Kẹp về key đầu là mọi animation có timeline bắt đầu muộn sẽ lệch ngay
     * từ frame 0 — đo trên Player/1 lệch 6,1% giá trị, riêng {@code set_3} sai tới 1,3 đơn vị.
     * {@code neutral} = 0 cho rotate/translate (cộng) và 1 cho scale (nhân), khớp
     * {@code bone.scaleX = bone.data.scaleX} của bản gốc.
     */
    private static float before(List<SpineData.Key> keys, float t, float neutral) {
        if (keys == null || keys.isEmpty() || t < keys.get(0).time) return neutral;
        return interp(keys, t);
    }

    /** Nội suy 1 timeline tại thời điểm t. Bezier absolute / stepped / linear. */
    private static float interp(List<SpineData.Key> keys, float t) {
        if (keys.isEmpty()) return 0;
        SpineData.Key first = keys.get(0);
        if (t <= first.time) return first.value;
        SpineData.Key last = keys.get(keys.size() - 1);
        if (t >= last.time) return last.value;
        for (int i = 1; i < keys.size(); i++) {
            SpineData.Key k = keys.get(i);
            if (t < k.time) {
                SpineData.Key prev = keys.get(i - 1);
                if (prev.stepped) return prev.value;
                if (prev.curve != null && prev.curve.length >= 4) {
                    if (prev.bez == null) {
                        prev.bez = bezierPoints(prev.time, prev.value, prev.curve, 0, k.time, k.value);
                    }
                    return bezierValue(prev.bez, 0, prev.time, prev.value, k.time, k.value, t);
                }
                float a = (t - prev.time) / (k.time - prev.time);
                return prev.value + (k.value - prev.value) * a;
            }
        }
        return last.value;
    }

    // ════════════════════ BEZIER ════════════════════════════════════════════
    /** Số float của MỘT đường cong đã lấy mẫu — {@code CurveTimeline.BEZIER_SIZE} (9 điểm × 2). */
    private static final int BEZ = 18;

    /**
     * Lấy mẫu cubic bezier thành 9 điểm bằng SAI PHÂN TIẾN, đúng {@code CurveTimeline.SetBezier}
     * ({@code Animation.cs:344-365}).
     *
     * <p><b>Vì sao không giải bezier chính xác:</b> spine-runtime cố tình chỉ lấy 9 điểm rồi nội
     * suy tuyến tính giữa chúng — tức là bản thân GAME chạy bằng phép xấp xỉ này. Bản cũ của tool
     * giải nghiệm chính xác bằng chia đôi nên "đúng toán" nhưng LỆCH so với hình người chơi thấy;
     * đo trên Player/1 lệch tới ~5% số giá trị. Muốn khớp Unity thì phải xấp xỉ y hệt nó.
     *
     * <p>Các hằng 0.03 / 0.006 / 0.3 / 0.16666667 là hệ số sai phân cho bước 1/10, chép nguyên bản.
     */
    private static float[] bezierPoints(float t0, float v0, float[] c, int off, float t1, float v1) {
        float cx1 = c[off], cy1 = c[off + 1], cx2 = c[off + 2], cy2 = c[off + 3];
        float[] p = new float[BEZ];
        float tmpx = (t0 - cx1 * 2 + cx2) * 0.03f, tmpy = (v0 - cy1 * 2 + cy2) * 0.03f;
        float dddx = ((cx1 - cx2) * 3 - t0 + t1) * 0.006f, dddy = ((cy1 - cy2) * 3 - v0 + v1) * 0.006f;
        float ddx = tmpx * 2 + dddx, ddy = tmpy * 2 + dddy;
        float dx = (cx1 - t0) * 0.3f + tmpx + dddx * 0.16666667f;
        float dy = (cy1 - v0) * 0.3f + tmpy + dddy * 0.16666667f;
        float x = t0 + dx, y = v0 + dy;
        for (int i = 0; i < BEZ; i += 2) {
            p[i] = x; p[i + 1] = y;
            dx += ddx; dy += ddy; ddx += dddx; ddy += dddy; x += dx; y += dy;
        }
        return p;
    }

    /**
     * Tra giá trị trên đường cong đã lấy mẫu — {@code CurveTimeline.GetBezierValue}
     * ({@code Animation.cs:372-390}): tìm đoạn chứa {@code t} rồi nội suy TUYẾN TÍNH trong đoạn đó.
     * {@code p} là mảng mẫu bắt đầu tại {@code off}.
     */
    private static float bezierValue(float[] p, int off, float t0, float v0, float t1, float v1, float t) {
        if (p[off] > t) return v0 + (t - t0) / (p[off] - t0) * (p[off + 1] - v0);
        for (int i = off + 2, n = off + BEZ; i < n; i += 2) {
            if (p[i] >= t) {
                float x = p[i - 2], y = p[i - 1];
                return y + (t - x) / (p[i] - x) * (p[i + 1] - y);
            }
        }
        float xx = p[off + BEZ - 2], yy = p[off + BEZ - 1];
        return yy + (t - xx) / (t1 - xx) * (v1 - yy);
    }

    private static final float DEG = (float) (Math.PI / 180.0);
    private static float cos(float deg) { return (float) Math.cos(deg * DEG); }
    private static float sin(float deg) { return (float) Math.sin(deg * DEG); }
}
