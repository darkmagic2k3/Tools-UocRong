package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index GUID → asset path. Unity ref asset bằng guid trong .meta.
 * Scan toàn bộ *.meta dưới 1 root, map guid → path file asset (bỏ .meta).
 *
 * Build 1 lần (chậm vài giây), cache trong RAM.
 */
public final class GuidIndex {

    private static final Pattern GUID = Pattern.compile("^guid:\\s*([0-9a-fA-F]{32})");

    private final Map<String, Path> guidToPath = new HashMap<>();

    /** Scan root (đệ quy) build index. */
    public void build(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Resource root không tồn tại: " + root);
        }
        long t0 = System.currentTimeMillis();
        final int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".meta")) {
                    String guid = readGuid(file);
                    if (guid != null) {
                        // asset path = .meta path bỏ đuôi .meta
                        Path asset = file.resolveSibling(name.substring(0, name.length() - 5));
                        guidToPath.put(guid, asset);
                        count[0]++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.printf("[GuidIndex] %d guid indexed in %dms%n", count[0], System.currentTimeMillis() - t0);
    }

    private static String readGuid(Path metaFile) {
        try {
            List<String> lines = Files.readAllLines(metaFile);
            for (String line : lines) {
                Matcher m = GUID.matcher(line.trim());
                if (m.find()) return m.group(1).toLowerCase();
            }
        } catch (IOException ignored) {
            // file lỗi → bỏ qua
        }
        return null;
    }

    /** Resolve guid → asset path, null nếu không có. */
    public Path resolve(String guid) {
        if (guid == null) return null;
        return guidToPath.get(guid.toLowerCase());
    }

    public int size() { return guidToPath.size(); }

    // ─── Disk cache (build 1 lần, reload nhanh) ────────────────

    /** Load cache nếu có → true; build từ scanRoot + save → false nếu phải build. */
    public boolean buildOrLoad(Path scanRoot, Path cacheFile) throws IOException {
        if (Files.exists(cacheFile)) {
            loadCache(cacheFile);
            System.out.printf("[GuidIndex] loaded %d guid từ cache %s%n", guidToPath.size(), cacheFile);
            return true;
        }
        build(scanRoot);
        saveCache(cacheFile);
        return false;
    }

    private void loadCache(Path cacheFile) throws IOException {
        for (String line : Files.readAllLines(cacheFile)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            guidToPath.put(line.substring(0, tab), Paths.get(line.substring(tab + 1)));
        }
    }

    private void saveCache(Path cacheFile) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Path> e : guidToPath.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue().toString()).append('\n');
            }
            Files.writeString(cacheFile, sb.toString());
            System.out.println("[GuidIndex] cache saved → " + cacheFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[GuidIndex] save cache fail: " + e.getMessage());
        }
    }
}
