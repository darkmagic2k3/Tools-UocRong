package com.apex.maptool.db;

import com.apex.maptool.config.ToolConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JDBC connection tới MySQL nro. Mỗi thao tác mở/đóng connection (tool nhẹ, ít query).
 */
public final class Db {
    private final ToolConfig cfg;

    public Db(ToolConfig cfg) {
        this.cfg = cfg;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Thiếu MySQL driver", e);
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(cfg.dbUrl(), cfg.dbUser(), cfg.dbPass());
    }
}
