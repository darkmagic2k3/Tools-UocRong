package com.apex.maptool.unity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bảng sorting layer của client — đọc từ {@code ProjectSettings/TagManager.asset}.
 *
 * <p>Ý nghĩa với chức năng "Bố cục Map": {@code m_SortingLayer} (index) quyết định phần tử map
 * vẽ TRƯỚC hay SAU nhân vật. Player dùng layer <b>LocalPlayer (index 18)</b>
 * (ActorVisual.ChangeLayerMask → LayerMask.LayerToName(16) = "LocalPlayer"):
 * <ul>
 *   <li>index &gt; 18 → vẽ ĐÈ lên player (tiền cảnh)</li>
 *   <li>index == 18 → NGANG player (so tiếp m_SortingOrder)</li>
 *   <li>index &lt; 18 → sau player</li>
 * </ul>
 *
 * <p>Trong prefab, {@code m_SortingLayerID} lưu bằng <b>int32 CÓ DẤU</b> của uniqueID
 * (vd UI: uniqueID 3774101189 → ghi -520866107) nên phải đổi qua lại bằng
 * {@link #toSignedId(long)} / {@link #toUnsignedId(int)}.
 */
public final class SortingLayers {

    /** 1 sorting layer: tên, uniqueID (unsigned 32-bit), index (thứ tự trong TagManager). */
    public record Layer(String name, long uniqueId, int index) {
        /** Giá trị đúng như ghi trong file prefab (m_SortingLayerID). */
        public int signedId() { return (int) uniqueId; }
    }

    /** Danh sách dự phòng (đo thật từ TagManager.asset ngày 2026-08-05) khi không đọc được file. */
    private static final Object[][] FALLBACK = {
            {"Default", 0L}, {"OneWay", 3856496035L}, {"Terrain", 756506221L},
            {"Player", 3155111061L}, {"PlayerChat", 3773756911L}, {"Notice", 2242409047L},
            {"Loading", 1544408929L}, {"Map", 1799343905L}, {"Ground", 297646877L},
            {"Shadow", 1511146119L}, {"KenMabu", 783248831L}, {"SkillsEffecy", 365911263L},
            {"NPC", 821996009L}, {"Enemy", 3073096545L}, {"Boss", 1658358587L},
            {"Pet", 2387955299L}, {"ItemDrop", 3582312091L}, {"RemotePlayer", 395904905L},
            {"LocalPlayer", 2655333707L}, {"Projectile", 1117496207L}, {"UI", 3774101189L}
    };

    private final List<Layer> layers;
    private final Map<Integer, Layer> byIndex = new HashMap<>();
    private final Map<Long, Layer> byUnsigned = new HashMap<>();
    private final Map<String, Layer> byName = new LinkedHashMap<>();
    private final boolean fromFile;
    private final int playerIndex;

    private SortingLayers(List<Layer> layers, boolean fromFile) {
        this.layers = List.copyOf(layers);
        this.fromFile = fromFile;
        for (Layer l : this.layers) {
            byIndex.putIfAbsent(l.index(), l);
            byUnsigned.putIfAbsent(l.uniqueId() & 0xFFFFFFFFL, l);
            byName.putIfAbsent(l.name().toLowerCase(), l);
        }
        Layer p = byName.get("localplayer");
        if (p == null) p = byName.get("player");
        this.playerIndex = (p != null) ? p.index() : 18;
    }

    /**
     * Đọc {@code <clientRepo>/ProjectSettings/TagManager.asset}.
     * Fail (thiếu file / parse rỗng) → dùng danh sách hard-code 21 layer + in cảnh báo.
     */
    public static SortingLayers load(Path clientRepo) {
        if (clientRepo != null) {
            Path tm = clientRepo.resolve("ProjectSettings").resolve("TagManager.asset");
            try {
                if (Files.exists(tm)) {
                    List<Layer> parsed = parse(Files.readAllLines(tm, StandardCharsets.UTF_8));
                    if (!parsed.isEmpty()) return new SortingLayers(parsed, true);
                    System.err.println("[SortingLayers] TagManager.asset không có m_SortingLayers → dùng bảng dự phòng");
                } else {
                    System.err.println("[SortingLayers] không thấy " + tm + " → dùng bảng dự phòng");
                }
            } catch (Exception e) {
                System.err.println("[SortingLayers] đọc TagManager.asset lỗi: " + e.getMessage()
                        + " → dùng bảng dự phòng");
            }
        } else {
            System.err.println("[SortingLayers] clientRepo null → dùng bảng dự phòng");
        }
        return fallback();
    }

    /** Bảng hard-code 21 layer (không đọc file). */
    public static SortingLayers fallback() {
        List<Layer> ls = new ArrayList<>(FALLBACK.length);
        for (int i = 0; i < FALLBACK.length; i++) {
            ls.add(new Layer((String) FALLBACK[i][0], (Long) FALLBACK[i][1], i));
        }
        return new SortingLayers(ls, false);
    }

    /**
     * Parse khối:
     * <pre>  m_SortingLayers:
     *   - name: Map
     *     uniqueID: 1799343905
     *     locked: 0</pre>
     */
    private static List<Layer> parse(List<String> lines) {
        List<Layer> out = new ArrayList<>();
        boolean in = false;
        String name = null;
        Long id = null;
        for (String raw : lines) {
            String t = raw.trim();
            if (!in) {
                if (t.equals("m_SortingLayers:")) in = true;      // "[]" (rỗng) → không vào
                continue;
            }
            if (t.startsWith("- name:")) {
                if (name != null) {                                // đóng entry trước
                    out.add(new Layer(name, id == null ? 0 : id, out.size()));
                }
                name = t.substring("- name:".length()).trim();
                id = null;
            } else if (t.startsWith("uniqueID:") && name != null) {
                try {
                    id = Long.parseLong(t.substring("uniqueID:".length()).trim());
                } catch (NumberFormatException ignored) { }
            } else if (t.startsWith("locked:")) {
                // bỏ qua
            } else if (!t.isEmpty() && !raw.startsWith("   ")
                    && (!raw.startsWith("  ") || !t.startsWith("-"))) {
                break;                                             // key indent-2 khác / dòng cột 0 → hết list
            }
        }
        if (name != null) out.add(new Layer(name, id == null ? 0 : id, out.size()));
        return out;
    }

    /** Toàn bộ layer theo đúng thứ tự index. */
    public List<Layer> all() { return Collections.unmodifiableList(layers); }

    /** true nếu đọc được từ TagManager.asset thật (false = đang dùng bảng dự phòng). */
    public boolean loadedFromFile() { return fromFile; }

    public Layer byIndex(int idx) { return byIndex.get(idx); }

    /** Nhận cả int32 CÓ DẤU (như ghi trong prefab) lẫn uniqueID unsigned. */
    public Layer byId(long id) { return byUnsigned.get(id & 0xFFFFFFFFL); }

    /** Tra theo tên, không phân biệt hoa thường. */
    public Layer byName(String name) {
        return name == null ? null : byName.get(name.trim().toLowerCase());
    }

    /** Index layer của nhân vật: "LocalPlayer" → "Player" → 18. */
    public int playerIndex() { return playerIndex; }

    /** uniqueID (unsigned) → int32 có dấu để GHI vào m_SortingLayerID. */
    public static int toSignedId(long uniqueId) { return (int) uniqueId; }

    /** int32 có dấu đọc từ prefab → uniqueID unsigned. */
    public static long toUnsignedId(int signedId) { return signedId & 0xFFFFFFFFL; }

    @Override public String toString() {
        return "SortingLayers[" + layers.size() + " layer, player=" + playerIndex
                + (fromFile ? ", từ TagManager.asset]" : ", bảng dự phòng]");
    }
}
