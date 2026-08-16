package com.apex.maptool.spine;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Hoà màu kiểu Spine cho Java2D — {@code AlphaComposite} chỉ có SRC_OVER nên additive/screen/multiply
 * phải tự tính từng điểm ảnh.
 *
 * <p><b>Vì sao cần:</b> đo trên client có <b>841 slot additive · 15 screen · 3 multiply</b> nằm ở
 * 390 skeleton (tia nắng, sóng biển, danh hiệu, màn login…). Với additive/screen thì màu ĐEN nghĩa là
 * trong suốt; vẽ bằng SRC_OVER thì nền đen của sprite hiện nguyên thành **mảng đen** đè lên map.
 *
 * <p><b>Công thức</b> bám shader của spine-unity, quy về nguồn đã nhân alpha ({@code s = src·srcA·alpha}):
 * <ul>
 *   <li>{@code Additive} — {@code Blend SrcAlpha One} ⇒ {@code out = dst + s}</li>
 *   <li>{@code Screen} — {@code Blend One OneMinusSrcColor} ⇒ {@code out = s + dst·(1 − s)}</li>
 *   <li>{@code Multiply} — {@code Blend DstColor OneMinusSrcAlpha} ⇒ {@code out = dst·(s + 1 − a)}</li>
 * </ul>
 * {@link SpineRenderer} bỏ premultiply của atlas khi cắt region nên ở đây nhận màu THẲNG, phải tự
 * nhân {@code srcA} vào — đừng bỏ bước đó, nếu không vùng trong suốt sẽ cộng thêm màu.
 *
 * <p>HIỆU NĂNG: chậm hơn SRC_OVER nhiều vì Java2D rơi về vòng lặp phần mềm. Chỉ dùng cho slot
 * THẬT SỰ có blend khác normal (đại đa số slot là normal nên đường vẽ chính không đụng tới lớp này).
 */
public final class BlendComposite implements Composite {

    /** Ép đi ĐƯỜNG CHẬM — chỉ để tự kiểm: đường nhanh phải cho ra ảnh y hệt đường chậm. */
    public static volatile boolean FORCE_GENERIC = false;

    private final int mode;      // SpineData.BLEND_*
    private final float alpha;   // alpha của slot × alpha sẵn có của Graphics2D

    public BlendComposite(int mode, float alpha) {
        this.mode = mode;
        this.alpha = alpha < 0 ? 0 : (alpha > 1 ? 1 : alpha);
    }

    /** null nếu chế độ này không cần composite riêng (normal ⇒ để AlphaComposite lo). */
    public static Composite of(int mode, float alpha) {
        return (mode == SpineData.BLEND_NORMAL) ? null : new BlendComposite(mode, alpha);
    }

    @Override
    public CompositeContext createContext(ColorModel srcCM, ColorModel dstCM, RenderingHints hints) {
        return new Ctx(mode, alpha);
    }

    /**
     * Bộ đệm dòng dùng chung theo LUỒNG.
     *
     * <p>Java2D tạo một {@link CompositeContext} MỚI cho <b>mỗi lần {@code drawImage}</b> — mà mesh
     * Spine vẽ từng TAM GIÁC một, nên một slot mesh 200 tam giác là 200 context/khung hình. Để
     * buffer làm field của context thì mỗi tam giác lại cấp phát mảng mới ⇒ rác khủng khiếp.
     * Giữ theo luồng nên cấp phát đúng một lần cho cả phiên vẽ.
     */
    private static final ThreadLocal<int[][]> SCRATCH = ThreadLocal.withInitial(() -> new int[2][]);

    private static final class Ctx implements CompositeContext {
        private final int mode;
        private final float alpha;

        Ctx(int mode, float alpha) { this.mode = mode; this.alpha = alpha; }

        @Override public void dispose() { }

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int w = Math.min(src.getWidth(), Math.min(dstIn.getWidth(), dstOut.getWidth()));
            int h = Math.min(src.getHeight(), Math.min(dstIn.getHeight(), dstOut.getHeight()));
            if (w <= 0 || h <= 0) return;

            Packed sp = FORCE_GENERIC ? null : Packed.of(src);
            Packed dp = FORCE_GENERIC ? null : Packed.of(dstIn);
            Packed op = FORCE_GENERIC ? null : Packed.of(dstOut);
            if (sp != null && dp != null && op != null && dp.sameLayout(op)) {
                composePacked(src, dstIn, dstOut, w, h, sp, dp);
            } else {
                composeGeneric(src, dstIn, dstOut, w, h);
            }
        }

        /**
         * Đường NHANH: raster kiểu int đóng gói (INT_RGB / INT_ARGB — gần như luôn là kiểu này, cả
         * ảnh nguồn của tool lẫn bộ đệm kép của Swing). Đọc/ghi bằng {@code getDataElements} nên
         * mỗi điểm ảnh chỉ là 1 int, không phải tách từng kênh qua SampleModel.
         *
         * <p>Mặt nạ bit lấy TỪ CHÍNH {@code SinglePixelPackedSampleModel} chứ không giả định
         * 0xFF0000/0xFF00/0xFF — sai mặt nạ là đảo màu mà chẳng có lỗi nào báo.
         */
        private void composePacked(Raster src, Raster dstIn, WritableRaster dstOut,
                                   int w, int h, Packed sp, Packed dp) {
            int[][] sc = SCRATCH.get();
            if (sc[0] == null || sc[0].length < w) sc[0] = new int[w];
            if (sc[1] == null || sc[1].length < w) sc[1] = new int[w];
            int[] sRow = sc[0], dRow = sc[1];

            int a255 = (int) (alpha * 255f + 0.5f);
            if (a255 <= 0) return;

            for (int y = 0; y < h; y++) {
                src.getDataElements(src.getMinX(), src.getMinY() + y, w, 1, sRow);
                dstIn.getDataElements(dstIn.getMinX(), dstIn.getMinY() + y, w, 1, dRow);
                for (int x = 0; x < w; x++) {
                    int s = sRow[x];
                    int sa = sp.aMask == 0 ? 255 : ((s & sp.aMask) >>> sp.aOff);
                    sa = mul255(sa, a255);                      // alpha nguồn × alpha slot
                    if (sa == 0) continue;

                    int d = dRow[x];
                    int sr = mul255((s & sp.rMask) >>> sp.rOff, sa);   // nguồn ĐÃ nhân alpha
                    int sg = mul255((s & sp.gMask) >>> sp.gOff, sa);
                    int sb = mul255((s & sp.bMask) >>> sp.bOff, sa);
                    int dr = (d & dp.rMask) >>> dp.rOff;
                    int dg = (d & dp.gMask) >>> dp.gOff;
                    int db = (d & dp.bMask) >>> dp.bOff;
                    int inv = 255 - sa;

                    int or, og, ob;
                    switch (mode) {
                        case SpineData.BLEND_ADDITIVE:
                            or = dr + sr; og = dg + sg; ob = db + sb;
                            break;
                        case SpineData.BLEND_SCREEN:
                            or = sr + mul255(dr, 255 - sr);
                            og = sg + mul255(dg, 255 - sg);
                            ob = sb + mul255(db, 255 - sb);
                            break;
                        case SpineData.BLEND_MULTIPLY:      // d·(s + 1 − a), thang 0..255
                            or = mul255(dr, clamp255(sr + inv));
                            og = mul255(dg, clamp255(sg + inv));
                            ob = mul255(db, clamp255(sb + inv));
                            break;
                        default:
                            or = sr + mul255(dr, inv);
                            og = sg + mul255(dg, inv);
                            ob = sb + mul255(db, inv);
                    }
                    int out = (clamp255(or) << dp.rOff) | (clamp255(og) << dp.gOff) | (clamp255(ob) << dp.bOff);
                    if (dp.aMask != 0) {
                        int da = (d & dp.aMask) >>> dp.aOff;
                        out |= clamp255(sa + mul255(da, inv)) << dp.aOff;
                    }
                    dRow[x] = out;
                }
                dstOut.setDataElements(dstOut.getMinX(), dstOut.getMinY() + y, w, 1, dRow);
            }
        }

        /** Đường CHẬM nhưng chạy với mọi kiểu raster — chỉ dùng khi raster không phải int đóng gói. */
        private void composeGeneric(Raster src, Raster dstIn, WritableRaster dstOut, int w, int h) {
            int sb = src.getNumBands(), db = dstIn.getNumBands();
            int[] sPix = new int[w * sb], dPix = new int[w * db];
            for (int y = 0; y < h; y++) {
                src.getPixels(src.getMinX(), src.getMinY() + y, w, 1, sPix);
                dstIn.getPixels(dstIn.getMinX(), dstIn.getMinY() + y, w, 1, dPix);
                for (int x = 0; x < w; x++) {
                    int si = x * sb, di = x * db;
                    float sa = (sb > 3 ? sPix[si + 3] / 255f : 1f) * alpha;
                    if (sa <= 0f) continue;
                    for (int c = 0; c < 3; c++) {
                        float s = (sPix[si + c] / 255f) * sa;
                        float d = dPix[di + c] / 255f;
                        float o;
                        switch (mode) {
                            case SpineData.BLEND_ADDITIVE: o = d + s; break;
                            case SpineData.BLEND_SCREEN:   o = s + d * (1f - s); break;
                            case SpineData.BLEND_MULTIPLY: o = d * (s + 1f - sa); break;
                            default:                       o = s + d * (1f - sa); break;
                        }
                        dPix[di + c] = clamp255((int) (o * 255f + 0.5f));
                    }
                    if (db > 3) {
                        float da = dPix[di + 3] / 255f;
                        dPix[di + 3] = clamp255((int) ((sa + da * (1f - sa)) * 255f + 0.5f));
                    }
                }
                dstOut.setPixels(dstOut.getMinX(), dstOut.getMinY() + y, w, 1, dPix);
            }
        }
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /**
     * {@code round(a·b/255)} bằng số nguyên, không chia thật.
     *
     * <p>Dùng {@code a*b/255} (cắt xuống) thì mỗi phép lệch tới 1 mức và LỆCH MỘT CHIỀU — chồng 2-3
     * phép là ảnh tối đi thấy được so với bản tính bằng float. Đây là mẹo chuẩn: cộng 128 rồi
     * {@code (t + (t>>8)) >> 8}.
     */
    private static int mul255(int a, int b) {
        int t = a * b + 128;
        return (t + (t >> 8)) >> 8;
    }

    /** Mặt nạ/dịch bit của raster int đóng gói. null = không phải kiểu đó ⇒ đi đường chậm. */
    private static final class Packed {
        final int rMask, gMask, bMask, aMask;
        final int rOff, gOff, bOff, aOff;

        private Packed(int[] masks, int[] offs) {
            rMask = masks[0]; gMask = masks[1]; bMask = masks[2];
            aMask = masks.length > 3 ? masks[3] : 0;
            rOff = offs[0]; gOff = offs[1]; bOff = offs[2];
            aOff = offs.length > 3 ? offs[3] : 0;
        }

        static Packed of(Raster r) {
            if (r.getTransferType() != java.awt.image.DataBuffer.TYPE_INT) return null;
            if (!(r.getSampleModel() instanceof java.awt.image.SinglePixelPackedSampleModel m)) return null;
            if (m.getNumBands() < 3) return null;
            return new Packed(m.getBitMasks(), m.getBitOffsets());
        }

        boolean sameLayout(Packed o) {
            return o != null && rMask == o.rMask && gMask == o.gMask && bMask == o.bMask && aMask == o.aMask
                    && rOff == o.rOff && gOff == o.gOff && bOff == o.bOff && aOff == o.aOff;
        }
    }
}
