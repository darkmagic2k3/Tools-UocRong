package com.apex.maptool.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        // 1. Load default từ classpath (đọc UTF-8 để path/giá trị tiếng Việt không hỏng)
        try (InputStream in = ToolConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (in != null) props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[ToolConfig] cannot load classpath config.properties: " + e.getMessage());
        }
        // 2. Override bằng config.properties NGOÀI (sửa không cần build lại jar)
        Path external = findExternalConfig();
        if (external != null) {
            try (InputStream in = Files.newInputStream(external)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
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

    /**
     * File cache GUID index — NEO vào thư mục tool, KHÔNG theo thư mục làm việc.
     *
     * <p>Trước đây trả path tương đối "guid-index.cache" nên chạy tool từ trong repo client
     * (vd cd vào đó rồi gọi java -cp) sẽ ném file cache vào repo client — đúng thứ tool cam kết
     * không bao giờ đụng tới. Neo theo vị trí jar: target/*.jar hoặc target/classes → lùi lên gốc tool.
     */
    public Path guidCacheFile() {
        Path dir = jarDir();
        if (dir != null) {
            // target/classes → lùi 2 cấp; target/ (chứa jar) → lùi 1 cấp; nơi khác → dùng luôn
            if (dir.getFileName() != null && dir.getFileName().toString().equals("classes")
                    && dir.getParent() != null) dir = dir.getParent();
            if (dir.getFileName() != null && dir.getFileName().toString().equals("target")
                    && dir.getParent() != null) dir = dir.getParent();
            return dir.resolve("guid-index.cache");
        }
        return Paths.get("guid-index.cache").toAbsolutePath();
    }

    public Path mapPrefab(int mapId) {
        return resourceRoot().resolve("Map" + mapId).resolve("Map_" + mapId + ".prefab");
    }

    /** Repo server (đọc enum ItemAttribute cho tên buff). */
    public Path serverRepo() {
        return Paths.get(props.getProperty("server.repo", "C:/Github/APEX-GAMES/UocRongOnline-Server").trim());
    }

    /** URL gateway Spring (đăng nhập/giftcode) — endpoint reload nạp lại code sau khi tool insert. */
    public String gatewayUrl() {
        return props.getProperty("gateway.url", "http://server.uocrong.vn:8001/home/").trim();
    }

    public String dbUrl() { return props.getProperty("db.url", ""); }
    public String dbUser() { return props.getProperty("db.user", "root"); }
    public String dbPass() { return props.getProperty("db.pass", ""); }

    /** File config.properties ngoài để ghi (nếu chưa có → tạo ở CWD). */
    public Path externalConfigTarget() {
        Path ext = findExternalConfig();
        return ext != null ? ext : Paths.get("config.properties");
    }

    /** Ghi db.url/db.user/db.pass vào config.properties ngoài (giữ nguyên các dòng khác). */
    public void saveDb(String url, String user, String pass) throws IOException {
        props.setProperty("db.url", url);
        props.setProperty("db.user", user);
        props.setProperty("db.pass", pass);
        Path target = externalConfigTarget();
        java.util.List<String> lines = Files.exists(target)
                ? new java.util.ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8))
                : new java.util.ArrayList<>();
        setOrAppend(lines, "db.url", url);
        setOrAppend(lines, "db.user", user);
        setOrAppend(lines, "db.pass", pass);
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    private static void setOrAppend(java.util.List<String> lines, String key, String val) {
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.startsWith(key + "=") || s.startsWith(key + " =")) { lines.set(i, key + "=" + val); return; }
        }
        lines.add(key + "=" + val);
    }

    public int pixelsPerUnit() {
        try { return Integer.parseInt(props.getProperty("render.pixelsPerUnit", "100").trim()); }
        catch (NumberFormatException e) { return 100; }
    }
}
