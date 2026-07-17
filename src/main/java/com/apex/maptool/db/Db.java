package com.apex.maptool.db;

import com.apex.maptool.config.ToolConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC tới MySQL nro qua CONNECTION POOL (HikariCP).
 *
 * Lý do: DB remote mở 1 connection mới tốn ~1.5-7s (server resolve chậm), trong khi query
 * chỉ ~3-25ms. Bản cũ mở/đóng connection MỖI thao tác → mỗi lần bấm tool đơ vài giây.
 * Pool giữ vài connection ấm sẵn và tái dùng → sau lần đầu, {@link #open()} lấy connection
 * gần như tức thì.
 *
 * Pool là SINGLETON tĩnh dùng chung toàn app (chỉ 1 DB) — mọi {@code new Db(cfg)} share cùng pool,
 * không tạo pool mới mỗi lần mở editor. Pool sống suốt đời JVM (đóng khi thoát app).
 */
public final class Db {
    private static volatile HikariDataSource DS;

    private final ToolConfig cfg;

    public Db(ToolConfig cfg) { this.cfg = cfg; }

    public Connection open() throws SQLException { return ds().getConnection(); }

    /**
     * Mở sẵn pool ở LUỒNG NỀN (gọi lúc app khởi động) để lần bấm tool đầu tiên
     * không phải trả ~3.5s/connection trên EDT.
     */
    public void warmUp() {
        try (Connection c = ds().getConnection()) {
            c.isValid(3);
            System.out.println("[Db] pool warm-up OK");
        } catch (SQLException e) {
            System.err.println("[Db] warm-up fail: " + e.getMessage());
        }
    }

    private HikariDataSource ds() {
        HikariDataSource d = DS;
        if (d == null) {
            synchronized (Db.class) {
                d = DS;
                if (d == null) DS = d = build(cfg);
            }
        }
        return d;
    }

    private static HikariDataSource build(ToolConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.dbUrl());
        hc.setUsername(cfg.dbUser());
        hc.setPassword(cfg.dbPass());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setPoolName("ur-tools");
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(2);                  // giữ 2 connection ấm sẵn
        hc.setConnectionTimeout(30_000);       // server mở connection có thể 1.5-7s
        hc.setValidationTimeout(5_000);
        hc.setKeepaliveTime(120_000);          // ping định kỳ giữ ấm + phát hiện rớt
        hc.setMaxLifetime(1_500_000);          // 25' < wait_timeout server, xoay vòng connection cũ
        hc.setInitializationFailTimeout(-1);   // không ném lúc tạo pool nếu DB tạm chưa reachable
        return new HikariDataSource(hc);
    }
}
