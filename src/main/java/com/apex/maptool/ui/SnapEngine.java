package com.apex.maptool.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bộ máy "HÍT KHÍT" (snap nam châm) cho màn Bố cục Map.
 *
 * <p>Lớp này THUẦN HÌNH HỌC — không đụng Swing (ngoài java.awt.geom nếu cần), không đụng
 * {@code MapScene}, nên chạy/kiểm thử headless được bằng
 * {@code java -cp target/classes com.apex.maptool.ui.SnapEngine}.
 *
 * <h3>Vì sao cần</h3>
 * Map thật ghép tile mép-nối-mép: Map37000 có 120/129 sprite khít dưới 1 px, Map13 các dải
 * {@code Lop_2_*} khít tuyệt đối 0.0000 unit. Map1/Map4 làm tay còn hở 0.16–1.6 px — đúng loại
 * lỗi mà bộ hít khít này dọn sạch.
 *
 * <h3>Thuật toán (tóm tắt)</h3>
 * <ol>
 *   <li><b>Ngưỡng theo PIXEL MÀN HÌNH</b>: {@code tolUnit = thresholdPx / scalePxPerUnit}.
 *       Zoom xa thì ngưỡng unit lớn (bắt được từ xa), zoom gần thì ngưỡng nhỏ (chỉnh từng px server).</li>
 *   <li><b>Hai trục ĐỘC LẬP</b>: tính {@code dx} và {@code dy} riêng — được phép khít X vào hình này
 *       và Y vào hình khác (đúng thực tế: tile vừa nối ngang vừa cùng đáy).</li>
 *   <li><b>Sinh ứng viên</b>: 3 mốc của hình đang kéo (min · tâm · max) × 3 mốc của từng hình khác
 *       (min · tâm · max) ⇒ 9 cặp/hình; cộng thêm mốc phụ {@code extraX/extraY} (đỉnh đường kẻ đất,
 *       4 thanh biên map), lưới, và gốc toạ độ 0.</li>
 *   <li><b>Chấm điểm</b>: {@code score = |lệch| × trọngSố}. Trọng số ưu tiên MÉP NỐI TIẾP
 *       (maxA↔minB / minA↔maxB — chính là ghép tile không hở khe) hơn mép trùng, mốc phụ, tâm, lưới, gốc.
 *       Chọn score nhỏ nhất; hoà (chênh trong cửa sổ hoà) thì ưu tiên loại có thứ hạng nhỏ hơn.</li>
 *   <li><b>Lọc theo trục CÒN LẠI</b>: chỉ xét hít X với hình có chồng lấn theo Y (nới rộng
 *       {@value #NEIGHBOR_PAD} unit) — tránh hít vào vật ở tận đầu map.</li>
 *   <li><b>Giới hạn hiệu năng</b>: {@code others.size() > }{@value #OTHERS_LIMIT} thì lọc trước theo
 *       khoảng cách tâm, giữ {@value #NEAR_KEEP} hình gần nhất.</li>
 * </ol>
 *
 * <h3>Cách canvas dùng</h3>
 * <pre>
 * SnapEngine.Result r = snap.snapRect(movingRect, otherRects, extraX, extraY, scale);
 * scene.setWorldCenter(node, cxChuột + r.dx(), cyChuột + r.dy());
 * // vẽ r.guidesX() thành đường DỌC tại x = target.value(), kéo từ from() tới to() (nới thêm 20 px)
 * // vẽ r.guidesY() thành đường NGANG tại y = target.value()
 * </pre>
 * Giữ <b>Alt</b> ⇒ canvas tự bỏ qua (không gọi lớp này) hoặc gọi {@link #setEnabled(boolean)} tạm tắt.
 */
public final class SnapEngine {

    // ─────────────────────────────────────────────────────────────────────
    // Kiểu dữ liệu công khai
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Loại mốc hít — đồng thời cho biết đường gióng nằm ở MÉP NÀO của hình đích.
     * Trục X dùng {@code EDGE_LEFT/EDGE_RIGHT/CENTER_X}; trục Y dùng {@code EDGE_BOTTOM/EDGE_TOP/CENTER_Y};
     * {@code VERTEX} = mốc phụ (đỉnh đường kẻ đất / 4 thanh biên map); {@code GRID} = lưới; {@code ORIGIN} = 0.
     */
    public enum Kind { EDGE_LEFT, EDGE_RIGHT, EDGE_TOP, EDGE_BOTTOM, CENTER_X, CENTER_Y, GRID, ORIGIN, VERTEX }

    /**
     * Một mục tiêu hít đã trúng — dùng để vẽ đường gióng cạnh.
     *
     * @param value toạ độ world của đường gióng (trục X ⇒ x cố định, trục Y ⇒ y cố định)
     * @param kind  loại mốc
     * @param label nhãn hiển thị tiếng Việt, ví dụ {@code "mép phải ↔ mép trái · Lop_2_7 (3)"}
     * @param from  đầu đoạn theo trục VUÔNG GÓC (guide X ⇒ from/to là Y; guide Y ⇒ from/to là X)
     * @param to    cuối đoạn theo trục vuông góc. {@code from == to} ⇒ không rõ phạm vi, canvas cứ vẽ hết màn hình.
     */
    public record Target(double value, Kind kind, String label, double from, double to) {}

    /** Hình chữ nhật world của 1 node (đã tính pivot/scale/xoay/chuỗi cha ở phía gọi). */
    public record Rect(double minX, double minY, double maxX, double maxY, String name) {
        /** Tâm X. */ public double cx() { return (minX + maxX) * 0.5; }
        /** Tâm Y. */ public double cy() { return (minY + maxY) * 0.5; }
        /** Bề rộng. */ public double w() { return maxX - minX; }
        /** Chiều cao. */ public double h() { return maxY - minY; }
    }

    /**
     * Kết quả hít.
     *
     * @param dx      lượng CẦN CỘNG THÊM vào toạ độ X để khít (0 = không hít)
     * @param dy      lượng cần cộng thêm vào toạ độ Y
     * @param guidesX các đường gióng DỌC (vẽ tại {@code x = value})
     * @param guidesY các đường gióng NGANG (vẽ tại {@code y = value})
     */
    public record Result(double dx, double dy, List<Target> guidesX, List<Target> guidesY) {
        /** Có hít trục X không. */ public boolean hitX() { return !guidesX.isEmpty(); }
        /** Có hít trục Y không. */ public boolean hitY() { return !guidesY.isEmpty(); }
        /** Có hít trục nào không. */ public boolean hit() { return hitX() || hitY(); }
    }

    /** Kết quả rỗng (tắt snap / không có ứng viên). */
    private static final Result NONE = new Result(0, 0, List.of(), List.of());

    // ─────────────────────────────────────────────────────────────────────
    // Hằng số
    // ─────────────────────────────────────────────────────────────────────

    /** Ngưỡng hít mặc định, tính bằng px màn hình. */
    public static final double DEFAULT_THRESHOLD_PX = 8.0;

    /** Nới rộng khi kiểm tra "hai hình có gần nhau theo trục còn lại không" (unit). */
    private static final double NEIGHBOR_PAD = 2.0;

    /** Quá số hình này thì lọc trước theo khoảng cách tâm. */
    private static final int OTHERS_LIMIT = 400;
    /** Số hình gần nhất giữ lại sau khi lọc. */
    private static final int NEAR_KEEP = 200;
    /** Tối đa bấy nhiêu đường gióng mỗi trục (tránh rối màn hình). */
    private static final int MAX_GUIDES = 8;

    /** Thứ hạng ưu tiên: nhỏ hơn = ưu tiên hơn (dùng khi score hoà). */
    private static final int R_CONTIGUOUS = 0, R_ALIGN = 1, R_VERTEX = 2, R_CENTER = 3, R_GRID = 4, R_ORIGIN = 5;
    /** Trọng số nhân vào |lệch| để ra score. */
    private static final double W_CONTIGUOUS = 1.00, W_ALIGN = 1.15, W_VERTEX = 1.30,
                                W_CENTER = 1.60, W_GRID = 2.00, W_ORIGIN = 2.20;

    /** Sai số so bằng của số thực. */
    private static final double EPS = 1e-9;

    /** Tên mốc trục X / trục Y (đưa vào nhãn đường gióng). */
    private static final String[] NAMES_X = { "mép trái", "tâm X", "mép phải" };
    private static final String[] NAMES_Y = { "mép đáy", "tâm Y", "mép đỉnh" };
    /** Tên mốc khi chỉ có 1 mốc (kéo đỉnh / kéo thanh biên). */
    private static final String[] NAMES_PT_X = { "điểm X" };
    private static final String[] NAMES_PT_Y = { "điểm Y" };
    private static final String[] NAMES_EDGE_X = { "cạnh dọc" };
    private static final String[] NAMES_EDGE_Y = { "cạnh ngang" };

    /** Kind của mốc đích theo chỉ số (0 = min, 1 = tâm, 2 = max). */
    private static final Kind[] KIND_X = { Kind.EDGE_LEFT, Kind.CENTER_X, Kind.EDGE_RIGHT };
    private static final Kind[] KIND_Y = { Kind.EDGE_BOTTOM, Kind.CENTER_Y, Kind.EDGE_TOP };

    // ─────────────────────────────────────────────────────────────────────
    // Trạng thái
    // ─────────────────────────────────────────────────────────────────────

    private boolean enabled = true;
    private double thresholdPx = DEFAULT_THRESHOLD_PX;
    private boolean gridOn = false;
    private double gridSize = 1.0;

    /** Tạo bộ hít khít với thiết lập mặc định: bật, ngưỡng 8 px, lưới tắt. */
    public SnapEngine() {}

    /** Bật/tắt toàn bộ hít khít (nút toolbar "Hít khít"). */
    public void setEnabled(boolean v) { this.enabled = v; }

    /** Đang bật hít khít không. */
    public boolean enabled() { return enabled; }

    /** Đặt ngưỡng hít theo PIXEL màn hình (thanh trượt 2..20 px). */
    public void setThresholdPx(double px) { this.thresholdPx = Math.max(0, px); }

    /** Ngưỡng hít hiện tại (px màn hình). */
    public double thresholdPx() { return thresholdPx; }

    /**
     * Bật/tắt hít lưới và đặt cỡ ô lưới (unit).
     * Lưới là mốc ưu tiên THẤP — chỉ thắng khi không có mép/tâm nào gần hơn.
     */
    public void setGrid(boolean on, double sizeUnit) {
        this.gridOn = on;
        if (sizeUnit > 0) this.gridSize = sizeUnit;
    }

    /** Lưới đang bật không. */
    public boolean gridOn() { return gridOn; }

    /** Cỡ ô lưới (unit). */
    public double gridSize() { return gridSize; }

    /**
     * Ngưỡng hít quy ra UNIT ở mức zoom cho trước.
     * Ví dụ: zoom mặc định 8 px/unit + ngưỡng 8 px ⇒ 1.0 unit; zoom 100 px/unit ⇒ 0.08 unit (8 px server).
     */
    public double tolUnit(double scalePxPerUnit) {
        if (scalePxPerUnit <= 0) return 0;
        return thresholdPx / scalePxPerUnit;
    }

    // ─────────────────────────────────────────────────────────────────────
    // API chính
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Tính hít cho HÌNH CHỮ NHẬT đang kéo.
     *
     * @param moving          hình chữ nhật world của node đang kéo (đã cộng chuyển động của chuột)
     * @param others          các hình khác trên map; hình TRÙNG TÊN hoặc trùng hệt {@code moving} bị BỎ QUA
     *                        (vì danh sách thường dựng trước khi kéo nên vẫn còn chính nó ở vị trí cũ)
     * @param extraX          mốc lẻ trên trục X (biên trái/phải map, đỉnh đường kẻ…), có thể null
     * @param extraY          mốc lẻ trên trục Y (biên đáy/đỉnh map, Y đỉnh đường kẻ đất…), có thể null
     * @param scalePxPerUnit  zoom hiện tại của canvas (px trên 1 unit)
     * @return {@link Result} với {@code dx/dy} cần cộng thêm và danh sách đường gióng
     */
    public Result snapRect(Rect moving, List<Rect> others,
                           double[] extraX, double[] extraY, double scalePxPerUnit) {
        if (!enabled || moving == null) return NONE;
        double tol = tolUnit(scalePxPerUnit);
        if (tol <= 0) return NONE;

        List<Rect> near = limitOthers(others, moving.cx(), moving.cy());

        // 3 mốc mỗi trục của hình đang kéo
        double[] mx = { moving.minX(), moving.cx(), moving.maxX() };
        double[] my = { moving.minY(), moving.cy(), moving.maxY() };

        List<Cand> cx = new ArrayList<>();
        List<Cand> cy = new ArrayList<>();
        collect(cx, true, mx, NAMES_X, moving.minY(), moving.maxY(), near, extraX, tol, true, true, moving);
        collect(cy, false, my, NAMES_Y, moving.minX(), moving.maxX(), near, extraY, tol, true, true, moving);

        return finish(cx, cy, tol);
    }

    /**
     * Tính hít cho 1 ĐIỂM (kéo đỉnh đường kẻ, kéo thanh biên map).
     * Điểm coi như hình chữ nhật suy biến ⇒ mỗi trục chỉ có 1 mốc, không có khái niệm "mép nối tiếp".
     *
     * @param x,y            toạ độ world của điểm (đã cộng chuyển động chuột)
     * @param others         các hình khác trên map
     * @param extraX,extraY  mốc lẻ (biên map, đỉnh đường kẻ khác…), có thể null
     * @param scalePxPerUnit zoom hiện tại (px/unit)
     */
    public Result snapPoint(double x, double y, List<Rect> others,
                            double[] extraX, double[] extraY, double scalePxPerUnit) {
        if (!enabled) return NONE;
        double tol = tolUnit(scalePxPerUnit);
        if (tol <= 0) return NONE;

        List<Rect> near = limitOthers(others, x, y);
        double[] mx = { x };
        double[] my = { y };

        List<Cand> cx = new ArrayList<>();
        List<Cand> cy = new ArrayList<>();
        // perpMin == perpMax == toạ độ trục còn lại của chính điểm đó
        collect(cx, true, mx, NAMES_PT_X, y, y, near, extraX, tol, false, true, null);
        collect(cy, false, my, NAMES_PT_Y, x, x, near, extraY, tol, false, true, null);

        return finish(cx, cy, tol);
    }

    /**
     * Tính hít khi kéo HANDLE SCALE — chỉ hít CẠNH đang kéo, không hít tâm hình đang kéo.
     *
     * <p>Vì chữ ký không mang thông tin cạnh nào (trái/phải/đáy/đỉnh), mốc min và max của hình khác
     * đều được xếp hạng "mép trùng"; phía gọi tự biết cạnh nào đang kéo qua tham số {@code horizontal}.
     * Cũng không có phạm vi trục vuông góc nên KHÔNG lọc lân cận — đổi lại {@code from/to} của đường
     * gióng lấy đúng phạm vi của hình đích.
     *
     * @param horizontal     {@code true} = {@code coord} là toạ độ <b>X</b> (kéo cạnh trái/phải, dịch ngang)
     *                       ⇒ kết quả nằm ở {@code dx}/{@code guidesX};
     *                       {@code false} = toạ độ <b>Y</b> (kéo cạnh đáy/đỉnh) ⇒ {@code dy}/{@code guidesY}
     * @param coord          toạ độ world hiện tại của cạnh đang kéo
     * @param scalePxPerUnit zoom hiện tại (px/unit)
     */
    public Result snapEdge(double coord, boolean horizontal, List<Rect> others, double scalePxPerUnit) {
        if (!enabled) return NONE;
        double tol = tolUnit(scalePxPerUnit);
        if (tol <= 0) return NONE;

        List<Rect> near = limitOthers(others, horizontal ? coord : 0, horizontal ? 0 : coord);
        double[] marks = { coord };
        List<Cand> cs = new ArrayList<>();
        // perpMin > perpMax ⇒ cờ "không rõ phạm vi": bỏ lọc lân cận, from/to lấy theo hình đích
        collect(cs, horizontal, marks, horizontal ? NAMES_EDGE_X : NAMES_EDGE_Y,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, near, null, tol, false, false, null);

        List<Target> g = new ArrayList<>();
        double d = pick(cs, g, tieWindow(tol));
        return horizontal ? new Result(d, 0, List.copyOf(g), List.of())
                          : new Result(0, d, List.of(), List.copyOf(g));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nội bộ
    // ─────────────────────────────────────────────────────────────────────

    /** 1 ứng viên hít: giá trị đích, độ lệch cần dịch, thứ hạng + điểm, và đoạn để vẽ guide. */
    private static final class Cand {
        final double value, delta, score;
        final int rank;
        final Kind kind;
        final String label;
        final double from, to;

        Cand(double value, double delta, int rank, double weight, Kind kind, String label, double from, double to) {
            this.value = value;
            this.delta = delta;
            this.rank = rank;
            this.score = Math.abs(delta) * weight;
            this.kind = kind;
            this.label = label;
            this.from = from;
            this.to = to;
        }
    }

    /** Gộp kết quả 2 trục (đã sinh ứng viên) thành {@link Result}. */
    private Result finish(List<Cand> cx, List<Cand> cy, double tol) {
        List<Target> gx = new ArrayList<>();
        List<Target> gy = new ArrayList<>();
        double tie = tieWindow(tol);
        double dx = pick(cx, gx, tie);
        double dy = pick(cy, gy, tie);
        return new Result(dx, dy, List.copyOf(gx), List.copyOf(gy));
    }

    /**
     * Cửa sổ "hoà điểm": chênh lệch score nhỏ hơn mức này thì coi như bằng nhau và xét thứ hạng.
     * Lấy min(0.02 unit, 1/4 ngưỡng) để ở zoom rất gần vẫn phân biệt được.
     */
    private static double tieWindow(double tol) { return Math.min(0.02, tol * 0.25); }

    /**
     * Sinh mọi ứng viên hít cho MỘT trục.
     *
     * @param out               nơi gom ứng viên
     * @param axisX             true = trục X (đường gióng dọc), false = trục Y
     * @param marks             các mốc của hình/điểm đang kéo (1 hoặc 3 phần tử: min · tâm · max)
     * @param markNames         tên hiển thị của từng mốc (cùng độ dài với {@code marks})
     * @param perpMin,perpMax   phạm vi của hình đang kéo theo trục VUÔNG GÓC.
     *                          {@code perpMin > perpMax} ⇒ "không rõ" (dùng cho {@link #snapEdge})
     * @param others            các hình đích (đã lọc số lượng)
     * @param extra             mốc lẻ trên trục này (có thể null)
     * @param tol               ngưỡng hít theo unit
     * @param contiguousAllowed cho phép xếp hạng "mép nối tiếp" (chỉ đúng khi có đủ 3 mốc)
     * @param filterPerp        có lọc theo chồng lấn trục vuông góc không
     * @param self              hình đang kéo, để BỎ QUA chính nó trong {@code others}
     */
    private void collect(List<Cand> out, boolean axisX,
                         double[] marks, String[] markNames,
                         double perpMin, double perpMax,
                         List<Rect> others, double[] extra,
                         double tol, boolean contiguousAllowed, boolean filterPerp, Rect self) {

        boolean perpKnown = perpMin <= perpMax;
        double fbFrom = perpKnown ? perpMin : 0;   // phạm vi dự phòng cho guide lưới/gốc/mốc phụ
        double fbTo   = perpKnown ? perpMax : 0;
        String[] tNames = axisX ? NAMES_X : NAMES_Y;
        Kind[] tKinds = axisX ? KIND_X : KIND_Y;

        // ── 1. Mốc từ các hình khác: 3 mốc kéo × 3 mốc đích ──
        for (Rect o : others) {
            if (isSelf(o, self)) continue;

            double oPerpMin = axisX ? o.minY() : o.minX();
            double oPerpMax = axisX ? o.maxY() : o.maxX();
            // Lọc lân cận: hít X chỉ có nghĩa khi hai hình gần nhau theo Y (và ngược lại)
            if (filterPerp && perpKnown && !overlap(perpMin, perpMax, oPerpMin, oPerpMax, NEIGHBOR_PAD)) continue;

            double[] tg = axisX ? new double[] { o.minX(), o.cx(), o.maxX() }
                                : new double[] { o.minY(), o.cy(), o.maxY() };
            double gFrom = Math.min(perpKnown ? perpMin : oPerpMin, oPerpMin);
            double gTo   = Math.max(perpKnown ? perpMax : oPerpMax, oPerpMax);

            for (int i = 0; i < marks.length; i++) {
                for (int j = 0; j < 3; j++) {
                    double d = tg[j] - marks[i];
                    if (Math.abs(d) > tol) continue;

                    // Mép NỐI TIẾP = mép phải chạm mép trái (hoặc đỉnh chạm đáy) → ghép tile không hở khe
                    boolean cont = contiguousAllowed && ((i == 2 && j == 0) || (i == 0 && j == 2));
                    int rank;
                    double w;
                    if (cont)                    { rank = R_CONTIGUOUS; w = W_CONTIGUOUS; }
                    else if (i == 1 || j == 1)   { rank = R_CENTER;     w = W_CENTER;     }
                    else                         { rank = R_ALIGN;      w = W_ALIGN;      }

                    String label = markNames[i] + (cont ? " ↔ " : " = ") + tNames[j] + " · " + safeName(o.name());
                    out.add(new Cand(tg[j], d, rank, w, tKinds[j], label, gFrom, gTo));
                }
            }
        }

        // ── 2. Mốc phụ: đỉnh đường kẻ đất, 4 thanh biên map… ──
        if (extra != null) {
            for (double e : extra) {
                for (int i = 0; i < marks.length; i++) {
                    double d = e - marks[i];
                    if (Math.abs(d) > tol) continue;
                    out.add(new Cand(e, d, R_VERTEX, W_VERTEX, Kind.VERTEX,
                            markNames[i] + " → mốc " + f(e), fbFrom, fbTo));
                }
            }
        }

        // ── 3. Lưới (ưu tiên thấp, chỉ khi bật) ──
        if (gridOn && gridSize > EPS) {
            for (int i = 0; i < marks.length; i++) {
                double g = Math.round(marks[i] / gridSize) * gridSize;
                double d = g - marks[i];
                if (Math.abs(d) > tol) continue;
                out.add(new Cand(g, d, R_GRID, W_GRID, Kind.GRID,
                        markNames[i] + " → lưới " + f(g), fbFrom, fbTo));
            }
        }

        // ── 4. Gốc toạ độ 0 (rẻ, ưu tiên thấp nhất) ──
        for (int i = 0; i < marks.length; i++) {
            double d = -marks[i];
            if (Math.abs(d) > tol) continue;
            out.add(new Cand(0, d, R_ORIGIN, W_ORIGIN, Kind.ORIGIN,
                    markNames[i] + " → gốc 0", fbFrom, fbTo));
        }
    }

    /**
     * Chọn ứng viên tốt nhất và gom các đường gióng.
     * Mọi ứng viên có CÙNG độ lệch với ứng viên thắng đều được vẽ (ví dụ vừa khít mép trái vào hình A
     * vừa khít mép phải vào hình B — cùng một {@code dx}).
     *
     * @return độ lệch cần cộng thêm (0 nếu không có ứng viên nào)
     */
    private static double pick(List<Cand> cands, List<Target> guides, double tie) {
        if (cands.isEmpty()) return 0;

        Cand best = cands.get(0);
        for (int i = 1; i < cands.size(); i++) if (better(cands.get(i), best, tie)) best = cands.get(i);

        List<Cand> same = new ArrayList<>();
        for (Cand c : cands) if (Math.abs(c.delta - best.delta) <= EPS) same.add(c);
        same.sort(Comparator.<Cand>comparingInt(c -> c.rank).thenComparingDouble(c -> c.score));

        for (Cand c : same) {
            if (guides.size() >= MAX_GUIDES) break;
            boolean dup = false;
            for (Target t : guides) {
                if (t.kind() == c.kind && Math.abs(t.value() - c.value) < EPS && t.label().equals(c.label)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) guides.add(new Target(c.value, c.kind, c.label, c.from, c.to));
        }
        return best.delta;
    }

    /** a có tốt hơn b không: score nhỏ hơn; hoà thì thứ hạng nhỏ hơn; hoà nữa thì lệch ít hơn. */
    private static boolean better(Cand a, Cand b, double tie) {
        double d = a.score - b.score;
        if (d < -tie) return true;
        if (d > tie) return false;
        if (a.rank != b.rank) return a.rank < b.rank;
        return Math.abs(a.delta) < Math.abs(b.delta);
    }

    /** Hai đoạn [aMin,aMax] và [bMin,bMax] có chạm nhau không khi nới rộng {@code pad} mỗi bên. */
    private static boolean overlap(double aMin, double aMax, double bMin, double bMax, double pad) {
        return aMin - pad <= bMax && aMax + pad >= bMin;
    }

    /**
     * Có phải chính hình đang kéo không. Bắt cả 3 kiểu: cùng tham chiếu, trùng hệt mọi thành phần,
     * và TRÙNG TÊN (quan trọng nhất — danh sách {@code others} thường dựng trước khi kéo nên chính
     * node đó vẫn nằm trong đó ở vị trí CŨ, nếu không loại sẽ bị "hít ngược về chỗ cũ").
     */
    private static boolean isSelf(Rect o, Rect self) {
        if (o == null) return true;
        if (self == null) return false;
        if (o == self || o.equals(self)) return true;
        String a = o.name(), b = self.name();
        return a != null && !a.isEmpty() && a.equals(b);
    }

    /** Giới hạn số hình đích để giữ hiệu năng khi map có hàng trăm node. */
    private static List<Rect> limitOthers(List<Rect> others, double cx, double cy) {
        if (others == null || others.isEmpty()) return List.of();
        if (others.size() <= OTHERS_LIMIT) return others;
        List<Rect> copy = new ArrayList<>(others);
        copy.sort(Comparator.comparingDouble(r -> dist2(r, cx, cy)));
        return copy.subList(0, NEAR_KEEP);
    }

    /** Bình phương khoảng cách từ tâm hình tới điểm (cx,cy). */
    private static double dist2(Rect r, double cx, double cy) {
        double ax = r.cx() - cx, ay = r.cy() - cy;
        return ax * ax + ay * ay;
    }

    private static String safeName(String s) { return (s == null || s.isEmpty()) ? "?" : s; }

    private static String f(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    // ─────────────────────────────────────────────────────────────────────
    // TỰ KIỂM (headless):  java -cp target/classes com.apex.maptool.ui.SnapEngine
    // ─────────────────────────────────────────────────────────────────────

    /** Số ca hỏng của lần tự kiểm gần nhất (chỉ dùng trong self-test). */
    private static int stFail = 0;
    private static int stPass = 0;

    private static void ck(String name, boolean ok, String detail) {
        if (ok) { stPass++; System.out.println("[PASS] " + name); }
        else    { stFail++; System.out.println("[FAIL] " + name + " → " + detail); }
    }

    private static boolean eq(double a, double b) { return Math.abs(a - b) < 1e-6; }

    /** Chạy toàn bộ ca kiểm, trả về SỐ CA HỎNG (không thoát tiến trình). */
    public static int runSelfTest() {
        stFail = 0;
        stPass = 0;
        SnapEngine s = new SnapEngine();          // bật, 8 px, lưới tắt

        // T1 — hít MÉP PHẢI vào MÉP TRÁI (ghép tile không hở khe)
        {
            Rect a = new Rect(0.5, 0, 10.5, 5, "A");
            List<Rect> o = List.of(new Rect(10.7, 0, 20.7, 5, "B"));
            Result r = s.snapRect(a, o, null, null, 8.0);          // tol = 1.0 unit
            ck("T1 hít mép phải→mép trái (hở 0.2 unit)",
               eq(r.dx(), 0.2) && r.hitX() && r.guidesX().get(0).kind() == Kind.EDGE_LEFT,
               "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T2 — hít TÂM X (chỉ tâm nằm trong ngưỡng)
        {
            Rect a = new Rect(20.0, 0.0, 24.0, 2.0, "A");           // tâm X = 22
            List<Rect> o = List.of(new Rect(21.5, 2.5, 22.9, 4.5, "B")); // tâm X = 22.2
            Result r = s.snapRect(a, o, null, null, 8.0);
            ck("T2 hít tâm X (lệch 0.2)",
               eq(r.dx(), 0.2) && r.hitX() && r.guidesX().get(0).kind() == Kind.CENTER_X,
               "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T3 — KHÔNG hít khi quá xa
        {
            Rect a = new Rect(20, 3, 24, 5, "A");
            List<Rect> o = List.of(new Rect(40, 3, 44, 5, "B"));
            Result r = s.snapRect(a, o, null, null, 8.0);
            ck("T3 không hít khi quá xa (16 unit ≫ 1.0)",
               eq(r.dx(), 0) && !r.hitX(), "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T4 — KHÔNG hít khi lệch trục còn lại (mép sát nhau theo X nhưng cách 25 unit theo Y)
        {
            Rect a = new Rect(20, 3, 24, 5, "A");
            List<Rect> o = List.of(new Rect(24.3, 30, 28.3, 32, "B"));
            Result r = s.snapRect(a, o, null, null, 8.0);
            ck("T4 không hít khi lệch trục còn lại",
               eq(r.dx(), 0) && !r.hitX(), "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T5 — hít LƯỚI
        {
            s.setGrid(true, 1.0);
            Rect a = new Rect(20.2, 3.1, 24.2, 5.1, "A");
            Result r = s.snapRect(a, List.of(), null, null, 8.0);
            boolean ok = eq(r.dx(), -0.2) && eq(r.dy(), -0.1)
                      && r.hitX() && r.guidesX().get(0).kind() == Kind.GRID;
            s.setGrid(false, 1.0);
            ck("T5 hít lưới 1.0 unit", ok, "dx=" + r.dx() + " dy=" + r.dy() + " guides=" + r.guidesX());
        }

        // T6 — TẮT snap ⇒ dx = dy = 0, không có guide
        {
            s.setEnabled(false);
            Rect a = new Rect(0.5, 0, 10.5, 5, "A");
            List<Rect> o = List.of(new Rect(10.7, 0, 20.7, 5, "B"));
            Result r = s.snapRect(a, o, null, null, 8.0);
            boolean ok = eq(r.dx(), 0) && eq(r.dy(), 0) && !r.hit();
            s.setEnabled(true);
            ck("T6 tắt hít khít ⇒ dx=dy=0", ok, "dx=" + r.dx() + " dy=" + r.dy());
        }

        // T7 — ZOOM NHỎ thì ngưỡng UNIT lớn hơn (cùng hình học: 8 px/u không bắt, 2 px/u bắt)
        {
            Rect a = new Rect(20, 0, 30, 5, "A");
            List<Rect> o = List.of(new Rect(33, 0, 40, 5, "B"));    // hở 3.0 unit
            Result near = s.snapRect(a, o, null, null, 8.0);        // tol = 1.0 → không bắt
            Result far  = s.snapRect(a, o, null, null, 2.0);        // tol = 4.0 → bắt
            ck("T7 zoom nhỏ ⇒ ngưỡng unit lớn hơn (1.0 vs 4.0)",
               eq(near.dx(), 0) && !near.hitX() && eq(far.dx(), 3.0) && far.hitX(),
               "gần=" + near.dx() + " xa=" + far.dx());
        }

        // T8 — ƯU TIÊN mép nối tiếp (lệch 0.5) HƠN tâm (lệch 0.4)
        {
            Rect a = new Rect(20, 0, 30, 2, "A");                   // mốc X: 20 · 25 · 30
            List<Rect> o = List.of(
                    new Rect(30.5, 0.5, 36.5, 2.5, "B"),            // mép trái 30.5 → nối tiếp, lệch 0.5
                    new Rect(22.4, 2.5, 28.4, 4.5, "C"));           // tâm 25.4 → tâm, lệch 0.4
            Result r = s.snapRect(a, o, null, null, 8.0);
            ck("T8 ưu tiên mép nối tiếp (0.5) hơn tâm (0.4)",
               eq(r.dx(), 0.5) && r.hitX() && r.guidesX().get(0).kind() == Kind.EDGE_LEFT
                       && r.guidesX().get(0).label().contains("B"),
               "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T9 — snapPoint hít MỐC PHỤ (đỉnh đường kẻ / biên map)
        {
            List<Rect> o = List.of(new Rect(30, 0, 40, 2, "xa"));
            Result r = s.snapPoint(20.15, 5.0, o, new double[] { 20.0, 50.0 }, null, 8.0);
            ck("T9 snapPoint hít mốc phụ (VERTEX)",
               eq(r.dx(), -0.15) && r.hitX() && r.guidesX().get(0).kind() == Kind.VERTEX && eq(r.dy(), 0),
               "dx=" + r.dx() + " dy=" + r.dy() + " guides=" + r.guidesX());
        }

        // T10 — snapEdge (kéo handle scale) hít mép trái của hình khác, guide lấy phạm vi hình đích
        {
            List<Rect> o = List.of(new Rect(24.0, 0, 30, 5, "T"));
            Result r = s.snapEdge(24.1, true, o, 8.0);
            Target g = r.hitX() ? r.guidesX().get(0) : null;
            ck("T10 snapEdge hít cạnh (lệch −0.1) + phạm vi guide đúng",
               eq(r.dx(), -0.1) && g != null && g.kind() == Kind.EDGE_LEFT && eq(g.from(), 0) && eq(g.to(), 5),
               "dx=" + r.dx() + " guide=" + g);
        }

        // T11 — BỎ QUA chính nó (others còn giữ node đang kéo ở vị trí CŨ, cùng tên)
        {
            Rect a = new Rect(20, 0, 30, 2, "A");
            List<Rect> o = List.of(
                    new Rect(20.4, 0, 30.4, 2, "A"),                // chính nó ở vị trí cũ → phải bỏ
                    new Rect(30.5, 0.5, 36.5, 2.5, "B"));
            Result r = s.snapRect(a, o, null, null, 8.0);
            ck("T11 bỏ qua chính nó trong danh sách",
               eq(r.dx(), 0.5), "dx=" + r.dx() + " guides=" + r.guidesX());
        }

        // T12 — giới hạn hiệu năng: 1000 hình vẫn hít đúng vào hình gần nhất
        {
            List<Rect> o = new ArrayList<>();
            o.add(new Rect(30.5, 0.5, 36.5, 2.5, "B"));
            for (int i = 0; i < 1000; i++) o.add(new Rect(500 + i * 10, 0, 505 + i * 10, 2, "xa" + i));
            Rect a = new Rect(20, 0, 30, 2, "A");
            long t0 = System.nanoTime();
            Result r = null;
            for (int k = 0; k < 200; k++) r = s.snapRect(a, o, null, null, 8.0);
            double ms = (System.nanoTime() - t0) / 1e6 / 200;
            ck("T12 1000 hình vẫn hít đúng, " + String.format(Locale.ROOT, "%.3f", ms) + " ms/lần",
               r != null && eq(r.dx(), 0.5) && ms < 5.0, "dx=" + (r == null ? "null" : r.dx()) + " ms=" + ms);
        }

        System.out.println("── SnapEngine selfTest: " + stPass + " PASS / " + stFail + " FAIL ──");
        return stFail;
    }

    /** Chạy tự kiểm rồi THOÁT với mã = số ca hỏng. */
    public static void selfTest() {
        System.exit(runSelfTest());
    }

    public static void main(String[] args) {
        selfTest();
    }
}
