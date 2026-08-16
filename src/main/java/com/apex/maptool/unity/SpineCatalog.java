package com.apex.maptool.unity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Danh mục MỌI skeleton Spine trong client — để chọn khi đổi skeleton của một node, hoặc khi thêm
 * node Spine mới vào map.
 *
 * <p>KHÔNG quét đĩa lần nữa: {@link GuidIndex} đã có đường dẫn của mọi asset, nên chỉ cần lọc
 * những file {@code *_SkeletonData.asset}. Mỗi cái cho luôn cả 3 thứ cần thiết:
 * <ul>
 *   <li><b>guid</b> để ghi vào {@code skeletonDataAsset} của {@code SkeletonAnimation};</li>
 *   <li><b>thư mục</b> chứa {@code .json}/{@code .skel.bytes} + {@code .atlas.txt} — đưa thẳng cho
 *       {@code SpineCharacter.load} để xem trước;</li>
 *   <li><b>guid material</b> ({@code *_Material.mat} cùng thư mục) để ghi vào {@code m_Materials}
 *       của MeshRenderer.</li>
 * </ul>
 *
 * <p>⚠ Đổi skeleton mà QUÊN đổi material là node vẽ ra bằng atlas của skeleton CŨ — hình sai hoàn
 * toàn mà Unity không báo lỗi gì. Hai guid phải đi cùng nhau.
 */
public final class SpineCatalog {

    /** 1 skeleton dùng được. */
    public static final class Entry implements Comparable<Entry> {
        /** Tên skeleton ({@code CayDua_SkeletonData.asset} → {@code CayDua}). */
        public final String name;
        /** Thư mục chứa skeleton (có .atlas.txt + .json/.skel.bytes). */
        public final Path folder;
        /** guid của {@code *_SkeletonData.asset}. */
        public final String skeletonGuid;
        /** guid của {@code *_Material.mat} cùng thư mục — null nếu không tìm thấy. */
        public final String materialGuid;
        /** Đường dẫn rút gọn từ {@code Assets/} để người dùng phân biệt trùng tên. */
        public final String shortPath;

        Entry(String name, Path folder, String skeletonGuid, String materialGuid, String shortPath) {
            this.name = name;
            this.folder = folder;
            this.skeletonGuid = skeletonGuid;
            this.materialGuid = materialGuid;
            this.shortPath = shortPath;
        }

        /** Có đủ file để VẼ được không (thiếu atlas/skeleton thì chỉ gán được guid, không xem trước). */
        public boolean drawable() {
            return folder != null && Files.isDirectory(folder);
        }

        @Override public int compareTo(Entry o) {
            int c = name.compareToIgnoreCase(o.name);
            return c != 0 ? c : shortPath.compareToIgnoreCase(o.shortPath);
        }

        @Override public String toString() { return name + "   (" + shortPath + ")"; }
    }

    private static final String SUFFIX_SKEL = "_SkeletonData.asset";
    private static final String SUFFIX_MAT = "_Material.mat";

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byGuid = new HashMap<>();

    /**
     * @param guidIndex index đã build (bắt buộc)
     * @param assetsRoot gốc {@code Assets} — chỉ dùng để rút gọn đường dẫn hiển thị
     */
    public SpineCatalog(GuidIndex guidIndex, Path assetsRoot) {
        if (guidIndex == null) return;

        // thư mục → guid material, để tra nhanh khi duyệt skeleton
        Map<Path, String> matByFolder = new HashMap<>();
        for (Map.Entry<String, Path> e : guidIndex.endingWith(SUFFIX_MAT)) {
            Path p = e.getValue();
            if (p != null && p.getParent() != null) matByFolder.putIfAbsent(p.getParent(), e.getKey());
        }

        for (Map.Entry<String, Path> e : guidIndex.endingWith(SUFFIX_SKEL)) {
            Path asset = e.getValue();
            if (asset == null || asset.getParent() == null) continue;
            String fn = asset.getFileName().toString();
            String name = fn.substring(0, fn.length() - SUFFIX_SKEL.length());
            Path folder = asset.getParent();
            Entry en = new Entry(name, folder, e.getKey(), matByFolder.get(folder),
                    shorten(folder, assetsRoot));
            entries.add(en);
            byGuid.put(e.getKey().toLowerCase(Locale.ROOT), en);
        }
        java.util.Collections.sort(entries);
    }

    private static String shorten(Path folder, Path assetsRoot) {
        String s = folder.toString().replace('\\', '/');
        if (assetsRoot != null) {
            String r = assetsRoot.toString().replace('\\', '/');
            if (s.startsWith(r)) s = s.substring(r.length());
        }
        return s.startsWith("/") ? s.substring(1) : s;
    }

    public List<Entry> all() { return java.util.Collections.unmodifiableList(entries); }

    public int size() { return entries.size(); }

    /** Skeleton theo guid của {@code *_SkeletonData.asset} (null nếu không có trong client). */
    public Entry byGuid(String guid) {
        return (guid == null) ? null : byGuid.get(guid.toLowerCase(Locale.ROOT));
    }

    /** Lọc theo chuỗi con, không phân biệt hoa thường, khớp cả tên lẫn đường dẫn. Rỗng = lấy hết. */
    public List<Entry> search(String q) {
        if (q == null || q.isBlank()) return all();
        String s = q.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.name.toLowerCase(Locale.ROOT).contains(s)
                    || e.shortPath.toLowerCase(Locale.ROOT).contains(s)) out.add(e);
        }
        return out;
    }
}
