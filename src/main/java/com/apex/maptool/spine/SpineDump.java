package com.apex.maptool.spine;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Đổ world transform của mọi bone ra file TSV để ĐỐI CHIẾU với spine-csharp thật.
 *
 * <p>Cách dùng (headless):
 * <pre>java -cp target/classes com.apex.maptool.MapToolApp spinedump &lt;thưMụcSpine&gt; &lt;oracle.tsv&gt; &lt;ra.tsv&gt;</pre>
 *
 * <p><b>Vì sao phải đọc {@code oracle.tsv} chứ không tự chọn mốc thời gian:</b> {@code duration}
 * của animation ở hai bên có thể lệch vài phần nghìn (mỗi bộ đọc tự suy ra từ timeline nó hiểu),
 * mà lệch mốc thời gian thì pose khác nhau ⇒ báo sai hàng loạt trong khi toán học vẫn đúng.
 * Lấy thẳng cặp (anim, t) và TÊN BONE từ file oracle nên phép so chỉ còn đo đúng một thứ:
 * cùng animation, cùng thời điểm, cùng bone thì ma trận world có khớp không.
 */
public final class SpineDump {

    private SpineDump() { }

    /** 1 dòng của file oracle — chỉ giữ 4 cột khoá, 6 cột số bên so tự đọc lại từ file gốc. */
    private record Row(String folder, String anim, float t, String bone) { }

    /**
     * {@code oracleTsv} có cột đầu là THƯ MỤC nên một lần chạy phủ được cả trăm skeleton —
     * khởi động JVM một lần thay vì mỗi asset một lần.
     */
    public static int run(String unusedFolder, String oracleTsv, String outTsv) {
        try {
            List<Row> rows = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get(oracleTsv), StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                rows.add(new Row(f[0], f[1], Float.parseFloat(f[2]), f[3]));
            }

            int missAnim = 0, missBone = 0, nFolder = 0, ikTotal = 0;
            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(outTsv), StandardCharsets.UTF_8)) {
                w.write("# folder\tanim\tt\tbone\ta\tb\tc\td\twx\twy\n");
                String curFolder = null, curAnim = null;
                float curT = Float.NaN;
                SpineData data = null;
                SpineSkeleton sk = null;

                for (Row r : rows) {
                    if (!r.folder.equals(curFolder)) {
                        data = loadData(r.folder);
                        sk = (data == null) ? null : new SpineSkeleton(data);
                        curFolder = r.folder;
                        curAnim = null;
                        curT = Float.NaN;
                        nFolder++;
                        if (data != null) ikTotal += data.iks.size();
                    }
                    if (data == null || sk == null) {
                        w.write(na(r));
                        continue;
                    }
                    // Chỉ pose lại khi sang khối (anim, t) mới — oracle ghi liền khối nên rẻ.
                    if (!r.anim.equals(curAnim) || r.t != curT) {
                        SpineData.Animation an = data.animations.get(r.anim);
                        if (an == null) missAnim++;
                        sk.pose(an, r.t);
                        curAnim = r.anim;
                        curT = r.t;
                    }
                    Integer bi = data.boneIdx.get(r.bone);
                    if (bi == null) { missBone++; w.write(na(r)); continue; }
                    int i = bi;
                    w.write(r.folder + "\t" + r.anim + "\t" + g9(r.t) + "\t" + r.bone + "\t"
                            + g9(sk.a[i]) + "\t" + g9(sk.b[i]) + "\t" + g9(sk.c[i]) + "\t" + g9(sk.d[i]) + "\t"
                            + g9(sk.wx[i]) + "\t" + g9(sk.wy[i]) + "\n");
                }
            }
            System.err.println("[spinedump] " + nFolder + " skeleton, " + ikTotal + " IK constraint, "
                    + rows.size() + " dong"
                    + (missAnim > 0 ? " THIEU_ANIM=" + missAnim : "")
                    + (missBone > 0 ? " THIEU_BONE=" + missBone : ""));
            return 0;
        } catch (Exception e) {
            System.err.println("[spinedump] loi: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    private static String na(Row r) {
        return r.folder + "\t" + r.anim + "\t" + g9(r.t) + "\t" + r.bone + "\tNA\tNA\tNA\tNA\tNA\tNA\n";
    }

    private static SpineData loadData(String folder) {
        try {
            Path json = null;
            try (Stream<Path> s = Files.list(Paths.get(folder))) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".json")) { json = p; break; }
                }
            }
            if (json == null) { System.err.println("[spinedump] khong thay .json: " + folder); return null; }
            return SpineData.load(json);
        } catch (Exception e) {
            System.err.println("[spinedump] load fail " + folder + ": " + e);
            return null;
        }
    }

    /** Cùng cách in với oracle C# ({@code ToString("G9")}) để mắt người so được, máy vẫn parse số. */
    private static String g9(float v) {
        if (Float.isNaN(v)) return "NaN";
        if (Float.isInfinite(v)) return v > 0 ? "Inf" : "-Inf";
        String s = String.format(Locale.ROOT, "%.9G", v);
        if (s.contains("E")) {                       // 1.23456789E+05 → gọn như C#
            s = s.replace("E+0", "E+").replace("E-0", "E-");
        }
        return s;
    }
}
