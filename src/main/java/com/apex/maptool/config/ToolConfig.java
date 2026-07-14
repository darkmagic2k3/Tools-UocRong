package com.apex.maptool.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Config tool — load từ config.properties (classpath) + override bằng file ngoài nếu có.
 *
 * PPU = 100 → 1 unity unit = 100 server coord = 100 px native.
 */
public final class ToolConfig {

    private final Properties props = new Properties();

    public ToolConfig() {
        // 1. Load default từ classpath
        try (InputStream in = ToolConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            System.err.println("[ToolConfig] cannot load classpath config.properties: " + e.getMessage());
        }
        // 2. Override bằng config.properties NGOÀI (sửa không cần build lại jar)
        Path external = findExternalConfig();
        if (external != null) {
            try (InputStream in = Files.newInputStream(external)) {
                props.load(in);
                System.out.println("[ToolConfig] override từ file ngoài: " + external.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[ToolConfig] cannot load external config: " + e.getMessage());
            }
        } else {
            System.out.println("[ToolConfig] không thấy config.properties ngoài → dùng default trong jar");
        }
    }

    /** Tìm config.properties ngoài: ưu tiên thư mục chạy (CWD), rồi cạnh jar, rồi thư mục cha của jar. */
    private static Path findExternalConfig() {
        java.util.List<Path> cands = new java.util.ArrayList<>();
        cands.add(Paths.get("config.properties"));            // CWD (vd double-click run.bat)
        Path jarDir = jarDir();
        if (jarDir != null) {
            cands.add(jarDir.resolve("config.properties"));   // cạnh jar (vd target/)
            if (jarDir.getParent() != null)
                cands.add(jarDir.getParent().resolve("config.properties")); // cha của jar (tool root)
        }
        for (Path p : cands) if (Files.exists(p)) return p;
        return null;
    }

    /** Thư mục chứa jar đang chạy (null nếu chạy từ classes/IDE). */
    private static Path jarDir() {
        try {
            Path p = Paths.get(ToolConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    public String clientRepo() { return props.getProperty("client.repo", "").trim(); }
    public String clientResource() { return props.getProperty("client.resource", "Assets/AssetBundles/Resource").trim(); }
    public String clientAssets() { return props.getProperty("client.assets", "Assets").trim(); }

    public Path resourceRoot() {
        return Paths.get(clientRepo(), clientResource());
    }

    /** Root scan GUID index (rộng hơn resource — material/texture rải ngoài). */
    public Path assetsRoot() {
        return Paths.get(clientRepo(), clientAssets());
    }

    /** File cache GUID index (cạnh thư mục chạy tool). */
    public Path guidCacheFile() {
        return Paths.get("guid-index.cache");
    }

    public Path mapPrefab(int mapId) {
        return resourceRoot().resolve("Map" + mapId).resolve("Map_" + mapId + ".prefab");
    }

    /** Repo server (đọc enum ItemAttribute cho tên buff). */
    public Path serverRepo() {
        return Paths.get(props.getProperty("server.repo", "C:/Github/APEX-GAMES/UocRongOnline-Server").trim());
    }

    public String dbUrl() { return props.getProperty("db.url", ""); }
    public String dbUser() { return props.getProperty("db.user", "root"); }
    public String dbPass() { return props.getProperty("db.pass", ""); }

    public int pixelsPerUnit() {
        try { return Integer.parseInt(props.getProperty("render.pixelsPerUnit", "100").trim()); }
        catch (NumberFormatException e) { return 100; }
    }
}
