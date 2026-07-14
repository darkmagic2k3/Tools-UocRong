package com.apex.maptool.spine;

import java.util.List;

/**
 * Tính world transform mỗi bone (setup pose + apply animation tại time t).
 * Spine matrix [a b; c d] + worldX/worldY. Y-up. Bỏ IK/constraint (FK thuần — đủ cho đa số idle).
 */
public final class SpineSkeleton {

    public final SpineData data;
    // per-bone world: a,b,c,d, worldX, worldY
    public final float[] a, b, c, d, wx, wy;

    public SpineSkeleton(SpineData data) {
        this.data = data;
        int n = data.bones.size();
        a = new float[n]; b = new float[n]; c = new float[n]; d = new float[n];
        wx = new float[n]; wy = new float[n];
    }

    /** Pose tại animation `anim` time t (giây). anim=null → setup pose. */
    public void pose(SpineData.Animation anim, float t) {
        List<SpineData.Bone> bones = data.bones;
        for (int i = 0; i < bones.size(); i++) {
            SpineData.Bone bo = bones.get(i);
            float rot = bo.rotation, x = bo.x, y = bo.y, sx = bo.scaleX, sy = bo.scaleY;
            float shX = bo.shearX, shY = bo.shearY;

            if (anim != null) {
                SpineData.BoneTimeline tl = anim.bones.get(bo.name);
                if (tl != null) {
                    if (tl.rotate != null) rot += interp(tl.rotate, t);
                    if (tl.tx != null) x += interp(tl.tx, t);
                    if (tl.ty != null) y += interp(tl.ty, t);
                    if (tl.sx != null) sx *= interp(tl.sx, t);
                    if (tl.sy != null) sy *= interp(tl.sy, t);
                }
            }

            float rotY = rot + 90 + shY;
            float la = cos(rot + shX) * sx;
            float lb = cos(rotY) * sy;
            float lc = sin(rot + shX) * sx;
            float ld = sin(rotY) * sy;

            if (bo.parentIdx < 0) {
                a[i] = la; b[i] = lb; c[i] = lc; d[i] = ld;
                wx[i] = x; wy[i] = y;
            } else {
                int p = bo.parentIdx;
                float pa = a[p], pb = b[p], pc = c[p], pd = d[p];
                wx[i] = pa * x + pb * y + wx[p];
                wy[i] = pc * x + pd * y + wy[p];
                a[i] = pa * la + pb * lc;
                b[i] = pa * lb + pb * ld;
                c[i] = pc * la + pd * lc;
                d[i] = pc * lb + pd * ld;
            }
        }
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
                    return bezierAbs(prev.time, prev.value, prev.curve, k.time, k.value, t);
                }
                float a = (t - prev.time) / (k.time - prev.time);
                return prev.value + (k.value - prev.value) * a;
            }
        }
        return last.value;
    }

    /**
     * Cubic bezier với control point TUYỆT ĐỐI [cx1,cy1,cx2,cy2].
     * X axis = time (t0→t1 qua cx1,cx2), Y axis = value (v0→v1 qua cy1,cy2). Cho time t → value.
     */
    private static float bezierAbs(float t0, float v0, float[] c, float t1, float v1, float t) {
        float cx1 = c[0], cy1 = c[1], cx2 = c[2], cy2 = c[3];
        // solve s: X(s) = t
        float lo = 0, hi = 1, s = (t - t0) / (t1 - t0);
        for (int i = 0; i < 18; i++) {
            float mt = 1 - s;
            float x = mt * mt * mt * t0 + 3 * mt * mt * s * cx1 + 3 * mt * s * s * cx2 + s * s * s * t1;
            if (Math.abs(x - t) < 0.0005f) break;
            if (x < t) lo = s; else hi = s;
            s = (lo + hi) / 2;
        }
        float mt = 1 - s;
        return mt * mt * mt * v0 + 3 * mt * mt * s * cy1 + 3 * mt * s * s * cy2 + s * s * s * v1;
    }

    private static final float DEG = (float) (Math.PI / 180.0);
    private static float cos(float deg) { return (float) Math.cos(deg * DEG); }
    private static float sin(float deg) { return (float) Math.sin(deg * DEG); }
}
