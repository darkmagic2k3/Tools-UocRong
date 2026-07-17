package com.apex.maptool.db;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Đọc/ghi giftcode. Bảng {@code gift_code} KHÔNG nằm ở DB game mà ở schema {@code nro_gateway}
 * (cùng MySQL server) — gateway Spring (UocRongOnline-Gateway) đọc bảng này.
 *
 * ⚠ Gateway CACHE toàn bộ code vào RAM lúc khởi động (GameManager.mapGiftCode) → insert xong
 * phải gọi {@link #reloadGateway()} (endpoint /home/reload) thì code mới dùng được ngay.
 *
 * Format cột theo schema THẬT của nro_gateway.gift_code (khác bản hibernate ở nro_restore!):
 *  - code            = varchar(20) — TỐI ĐA 20 KÝ TỰ
 *  - time_start/end  = timestamp NOT NULL (không nhận NULL; phạm vi 1970..2038)
 *  - list_gift_str   = "infoId-soLuong;infoId-soLuong;..." (item_info_config id)
 *  - list_server_str = "1" | "1,2" | "" (rỗng = mọi server)
 *  - list_player_id_str = danh sách playerId đã dùng, phân cách "," (mới tạo = "")
 *  - max = số lượt dùng tối đa (0 = không giới hạn)
 *  - type_code = 0 thường / 1 daily / 2 vip theo đợt (index_code)
 */
public final class GiftcodeDao {

    /** Bảng gift_code nằm ở schema gateway — qualify cứng vì pool kết nối vào DB game. */
    private static final String TABLE = "nro_gateway.gift_code";

    private final Db db;
    private final String gatewayUrl;   // vd http://server.uocrong.vn:8001/home/

    public GiftcodeDao(Db db, String gatewayUrl) {
        this.db = db;
        this.gatewayUrl = gatewayUrl == null || gatewayUrl.isBlank() ? null
                : (gatewayUrl.endsWith("/") ? gatewayUrl : gatewayUrl + "/");
    }

    // ─── model ─────────────────────────────────────────────────
    public static final class GiftCode {
        public String code;
        public int indexCode;
        public String listGiftStr;       // "259-99;260-99"
        public String listPlayerIdStr;   // "" khi mới tạo
        public String listServerStr;     // "1" / "1,2" / ""
        public int max;                  // 0 = không giới hạn
        public Timestamp timeStart;      // NOT NULL trên bảng gateway
        public Timestamp timeEnd;        // NOT NULL trên bảng gateway
        public int typeCode;             // 0/1/2

        /** Số người đã dùng (đếm phần tử list_player_id_str). */
        public int used() {
            if (listPlayerIdStr == null || listPlayerIdStr.isBlank()) return 0;
            int n = 1;
            for (int i = 0; i < listPlayerIdStr.length(); i++)
                if (listPlayerIdStr.charAt(i) == ',') n++;
            return n;
        }

        public GiftCode copyNoCode() {
            GiftCode g = new GiftCode();
            g.indexCode = indexCode;
            g.listGiftStr = listGiftStr;
            g.listPlayerIdStr = "";
            g.listServerStr = listServerStr;
            g.max = max;
            g.timeStart = timeStart;
            g.timeEnd = timeEnd;
            g.typeCode = typeCode;
            return g;
        }
    }

    // ─── load ──────────────────────────────────────────────────
    /** Toàn bộ code, mới nhất trước. */
    public List<GiftCode> list() throws SQLException {
        List<GiftCode> out = new ArrayList<>();
        String sql = "SELECT code, index_code, list_gift_str, list_player_id_str, list_server_str, `max`, "
                + "time_start, time_end, type_code FROM " + TABLE
                + " ORDER BY time_start IS NULL, time_start DESC, code";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                GiftCode g = new GiftCode();
                g.code = rs.getString("code");
                g.indexCode = rs.getInt("index_code");
                g.listGiftStr = rs.getString("list_gift_str");
                g.listPlayerIdStr = rs.getString("list_player_id_str");
                g.listServerStr = rs.getString("list_server_str");
                g.max = rs.getInt("max");
                g.timeStart = rs.getTimestamp("time_start");
                g.timeEnd = rs.getTimestamp("time_end");
                g.typeCode = rs.getInt("type_code");
                out.add(g);
            }
        }
        return out;
    }

    /** Set toàn bộ code hiện có (check trùng khi sinh random). */
    public Set<String> allCodes() throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection c = db.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT code FROM " + TABLE)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Template item (id + tên) từ DB game — cho picker chọn quà. */
    public List<ShopDao.ItemInfo> itemInfos() throws SQLException {
        List<ShopDao.ItemInfo> out = new ArrayList<>();
        try (Connection c = db.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM item_info_config ORDER BY id")) {
            while (rs.next()) out.add(new ShopDao.ItemInfo(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }

    // ─── ghi ───────────────────────────────────────────────────
    /**
     * Insert 1 code. Trả {@code false} nếu code đã tồn tại (trùng PK) — caller sinh code khác thử lại.
     * Lỗi khác (mất kết nối, sai format...) ném SQLException như thường.
     */
    public boolean tryInsert(GiftCode g) throws SQLException {
        validate(g);
        String sql = "INSERT INTO " + TABLE
                + " (code, index_code, list_gift_str, list_player_id_str, list_server_str, `max`, time_end, time_start, type_code)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, g.code);
            ps.setInt(2, g.indexCode);
            ps.setString(3, g.listGiftStr);
            ps.setString(4, g.listPlayerIdStr == null ? "" : g.listPlayerIdStr);
            ps.setString(5, g.listServerStr == null ? "" : g.listServerStr);
            ps.setInt(6, g.max);
            ps.setTimestamp(7, g.timeEnd);
            ps.setTimestamp(8, g.timeStart);
            ps.setInt(9, g.typeCode);
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException dup) {
            return false;
        }
    }

    public void delete(String code) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE code=?")) {
            ps.setString(1, code);
            System.out.println("[GiftcodeDao] delete " + code + " rows=" + ps.executeUpdate());
        }
    }

    private static void validate(GiftCode g) throws SQLException {
        if (g.code == null || g.code.isBlank()) throw new SQLException("Code rỗng.");
        if (g.code.length() > 20) throw new SQLException("Code quá dài — cột code là varchar(20).");
        if (g.timeStart == null || g.timeEnd == null)
            throw new SQLException("Thiếu thời gian bắt đầu/hết hạn (cột timestamp NOT NULL).");
        if (g.listGiftStr == null || g.listGiftStr.isBlank())
            throw new SQLException("Chưa có quà — code nhập xong sẽ không nhận gì.");
        if (!g.listGiftStr.matches("\\d+-\\d+(;\\d+-\\d+)*"))
            throw new SQLException("list_gift_str sai format (cần 'infoId-soLuong;...'): " + g.listGiftStr);
        if (g.listGiftStr.length() > 255)
            throw new SQLException("Danh sách quà quá dài (>255 ký tự) — bớt item.");
        if (g.listServerStr != null && !g.listServerStr.isBlank() && !g.listServerStr.matches("\\d+(,\\d+)*"))
            throw new SQLException("Server sai format (cần '1' hoặc '1,2'): " + g.listServerStr);
        if (g.timeStart != null && g.timeEnd != null && g.timeEnd.before(g.timeStart))
            throw new SQLException("Hết hạn trước cả thời điểm bắt đầu.");
    }

    // ─── gateway reload ────────────────────────────────────────
    /**
     * Gọi GET {gateway}/reload → gateway nạp lại gift_code từ DB vào RAM.
     * KHÔNG gọi thì code mới chỉ có hiệu lực sau khi restart gateway.
     */
    public String reloadGateway() throws Exception {
        if (gatewayUrl == null) throw new IllegalStateException("Chưa cấu hình gateway.url trong config.properties");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayUrl + "reload"))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IllegalStateException("Gateway trả HTTP " + res.statusCode() + ": " + res.body());
        return res.body();
    }
}
