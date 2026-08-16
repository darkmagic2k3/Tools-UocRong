package com.apex.maptool.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tên option (chỉ số) của item — 2 nguồn, DB là nguồn CHÍNH:
 *
 * <ol>
 *   <li>Bảng {@code buff_info} (id, `desc`) trong DB — đây là danh sách thật server/client dùng.
 *       Thêm option mới ở đây là tool thấy ngay, KHÔNG cần đụng source.</li>
 *   <li>Enum {@code game/enums/ItemAttribute.java} trong repo server — chỉ làm nền/dự phòng
 *       khi DB chưa kết nối được. Enum này hay bị bỏ quên (thiếu option 174-189 so với DB).</li>
 * </ol>
 *
 * Trùng id → DB THẮNG (source enum có thể lỗi thời).
 */
public final class AttrNames {

    private static final Pattern ENTRY = Pattern.compile("\\(\\s*\"([^\"]+)\"\\s*,\\s*(\\d+)\\s*\\)");

    private final Map<Integer, String> names = new HashMap<>();
    private final Path source;
    private String problem;   // null = OK; khác null = lý do map rỗng (hiện lên dialog)
    private int fileCount;    // số option đọc từ enum source
    private int dbCount;      // số option đọc từ bảng buff_info
    private String dbProblem; // lý do không đọc được buff_info (null = OK / không truyền db)

    /** Chỉ đọc enum source (không có DB) — giữ cho chỗ gọi cũ. */
    public AttrNames(Path serverRepo) { this(serverRepo, null); }

    /**
     * Đọc enum source làm nền rồi ĐÈ bằng bảng {@code buff_info} của DB (option mới nằm ở đây).
     * db = null → chỉ dùng enum source.
     */
    public AttrNames(Path serverRepo, Db db) {
        source = serverRepo.resolve("src/main/java/game/enums/ItemAttribute.java");
        loadFromSource();
        if (db != null) loadFromDb(db);
        if (names.isEmpty() && problem == null) problem = "Không lấy được option từ cả DB lẫn source";
        System.out.println("[AttrNames] tổng " + names.size() + " option (buff_info DB=" + dbCount
                + ", enum source=" + fileCount + ")");
    }

    /** Enum ItemAttribute trong repo server — nền/dự phòng. Lỗi → bỏ qua, chờ DB. */
    private void loadFromSource() {
        try {
            if (Files.exists(source)) {
                // decode "dễ tính": 1 byte lạ giữa file không được phép xóa sổ TOÀN BỘ danh sách buff
                for (String line : readLenient(source)) {
                    Matcher m = ENTRY.matcher(line);
                    if (m.find()) names.put(Integer.parseInt(m.group(2)), m.group(1));
                }
                fileCount = names.size();
                if (fileCount == 0) problem = "File có nhưng không parse được entry nào (đổi format enum?)";
            } else {
                problem = "Không thấy file";
                System.err.println("[AttrNames] không thấy " + source);
            }
        } catch (Exception e) {
            problem = "Đọc fail: " + e.getMessage();
            System.err.println("[AttrNames] parse source fail: " + e.getMessage());
        }
    }

    /**
     * Bảng buff_info (id, `desc`) — danh sách option THẬT. Đè lên enum source vì source hay lỗi thời.
     * Đọc fail (mất mạng/bảng đổi tên) → giữ nguyên list từ source, chỉ ghi log.
     */
    private void loadFromDb(Db db) {
        try (Connection c = db.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, `desc` FROM buff_info ORDER BY id")) {
            while (rs.next()) {
                String d = rs.getString(2);
                if (d == null || d.isBlank()) continue;
                names.put(rs.getInt(1), d.trim());
                dbCount++;
            }
            if (dbCount > 0) problem = null;   // DB có dữ liệu → lỗi source không còn quan trọng
            else dbProblem = "Bảng buff_info rỗng";
        } catch (Exception e) {
            dbProblem = e.getMessage();
            System.err.println("[AttrNames] đọc buff_info fail: " + e.getMessage() + " → dùng enum source");
        }
    }

    /** Đọc UTF-8, byte hỏng → thay ký tự thay vì ném MalformedInputException. */
    private static java.util.List<String> readLenient(Path f) throws java.io.IOException {
        return java.util.Arrays.asList(
                new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8).split("\\R"));
    }

    /** File enum đang đọc — cho thông báo lỗi chỉ đúng chỗ cần sửa. */
    public Path source() { return source; }

    /** Lý do danh sách rỗng (đường dẫn + nguyên nhân) — null nếu load OK. */
    public String problem() { return problem; }

    /** Số option lấy từ bảng buff_info (0 = không đọc được DB, đang chạy bằng enum source). */
    public int dbCount() { return dbCount; }

    /** Mô tả nguồn cho thanh trạng thái: "buff_info DB: 100 option" / "enum source: 84 option". */
    public String sourceLabel() {
        if (dbCount > 0) return "bảng buff_info (DB): " + dbCount + " option";
        return "enum ItemAttribute (source server): " + fileCount + " option — CHƯA đọc được buff_info trong DB";
    }

    /** Thông báo lỗi đầy đủ cho dialog (path + nguyên nhân + chỗ sửa). */
    public String errorText() {
        return "Không lấy được danh sách option (chỉ số) — cả DB lẫn source đều fail.\n\n"
                + "1) Bảng buff_info trong DB\n→ " + (dbProblem != null ? dbProblem : "không truyền kết nối DB")
                + "\n\n2) " + source + "\n→ " + (problem != null ? problem : "?")
                + "\n\nSửa 'db.*' và 'server.repo' trong config.properties.";
    }

    /** Mô tả buff: thay # bằng value + TÍNH luôn "(x / y)" → số gọn ("HP + (500/100) %" → "HP + 5 %"). */
    public String describe(int type, long value) {
        String d = names.get(type);
        if (d == null) return "buff " + type + " = " + value;
        String s = d.contains("#") ? d.replace("#", String.valueOf(value)) : d + ": " + value;
        return simplifyMath(s);
    }

    private static final Pattern DIV = Pattern.compile("\\(\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*\\)");

    /** "(500 / 100)" → "5"; "(50 / 100)" → "0.5". */
    static String simplifyMath(String s) {
        Matcher m = DIV.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double v = Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
            String r = (v == Math.floor(v)) ? String.valueOf((long) v)
                    : String.valueOf(Math.round(v * 100) / 100.0);
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public boolean known(int type) { return names.containsKey(type); }
    public int size() { return names.size(); }

    /** Mô tả gốc (template chứa #) — null nếu không biết. */
    public String raw(int type) { return names.get(type); }

    /**
     * Tên gọn cho cột "Tên option": BỎ chỗ điền số, giữ nguyên phần chữ.
     * "HP + #" → "HP" · "Chí mạng + (# / 100) %" → "Chí mạng" · "Tăng #% sát thương khi bị đánh"
     * → "Tăng sát thương khi bị đánh" · "+# TNSM cho đệ tử..." → "TNSM cho đệ tử...".
     * (Bản cũ cắt cụt tại dấu đầu tiên nên mô tả có # ở GIỮA chỉ còn 1 chữ: "Tăng", "Hồi".)
     */
    public String shortName(int type) {
        String d = names.get(type);
        if (d == null) return "option " + type;
        String r = d.replaceAll("\\(\\s*#\\s*/\\s*\\d+(?:\\.\\d+)?\\s*\\)\\s*%?", "")   // (# / 100) %
                .replaceAll("#\\s*/\\s*\\d+", "")                                        // #/200
                .replaceAll("#\\s*%?", "")                                               // # · #%
                .replaceAll("\\s{2,}", " ")
                .replaceAll("^[\\s+:,\\-]+", "")
                .replaceAll("[\\s+:,\\-]+$", "")
                .trim();
        return r.isEmpty() ? d : r;
    }

    /** Tất cả buff (id, desc) sort theo id — cho picker. */
    public java.util.List<java.util.Map.Entry<Integer, String>> entries() {
        return names.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toList());
    }
}
