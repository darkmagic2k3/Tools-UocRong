package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unity YAML prefab đọc theo DÒNG — patch "phẫu thuật" (chỉ sửa đúng dòng cần sửa,
 * mọi dòng khác giữ nguyên từng byte, KHÔNG re-serialize cả file).
 *
 * <p>Cơ sở (đo thật trên 156 prefab map, xem tài liệu 01-prefab-format.md):
 * <ul>
 *   <li>File UTF-8 không BOM, line ending CRLF 100 %, có newline ở cuối file.</li>
 *   <li>Block mở đầu bằng dòng {@code --- !u!<classId> &<anchor>[ stripped]} ở cột 0,
 *       dòng ngay sau là tên type ({@code GameObject:}, {@code Transform:}…).</li>
 *   <li>Trong 1 block KHÔNG có key nào ở indent 2 bị lặp ⇒ cặp (anchor, key) xác định
 *       duy nhất 1 dòng. Nhiều key trùng tên giữa các block (m_Enabled, m_Size, m_Offset…)
 *       nên LUÔN phải tìm trong phạm vi block, không quét toàn file.</li>
 * </ul>
 *
 * <p>Cách dùng: {@code new PrefabDocument(prefab)} → tìm block → {@code setScalar/setVec2/
 * setQuatZ/setPointList} → {@code save(backupDir)} (ghi đè file gốc, backup trước)
 * hoặc {@code saveAs(target)} (ghi ra file khác — dùng khi TEST, không đụng file client).
 */
public final class PrefabDocument {

    // --- !u!212 &5003727711088462726  (có thể kèm " stripped")
    private static final Pattern BLOCK =
            Pattern.compile("^--- !u!(\\d+) &(-?\\d+)( stripped)?\\s*$");
    // dòng type ngay sau header block: "GameObject:", "SpriteRenderer:"…
    private static final Pattern TYPE = Pattern.compile("^([A-Za-z_][A-Za-z_0-9]*):\\s*$");
    private static final Pattern FILEID = Pattern.compile("fileID:\\s*(-?\\d+)");
    private static final Pattern GUID = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");
    // thành phần của map inline "{x: 1, y: 2, z: 0}" — key phải theo sau bởi SỐ
    private static final Pattern COMP =
            Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*:\\s*(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)");
    private static final Pattern EOL_SPLIT = Pattern.compile("\r\n|\n|\r");

    // ── hằng dùng cho việc CHÈN / XOÁ nguyên block (tài liệu 04) ──
    /** classId của GameObject — bất biến "block !u!1 có anchor tăng dần" quyết định chỗ chèn. */
    private static final int CLS_GAMEOBJECT = 1;
    /** classId của PrefabInstance — mốc kết thúc "vùng object riêng" của file. */
    private static final int CLS_PREFAB_INSTANCE = 1001;
    /**
     * Anchor mới luôn ≥ 10^17 (18–19 chữ số) — đúng dải Unity dùng cho 30 736/31 590 anchor đo
     * được trên 156 map, đồng thời né sạch các fileID "có ý nghĩa" của Unity (&lt; 2^31 như
     * 2000000000, 21300000, 11500000…).
     */
    private static final long MIN_ANCHOR = 100_000_000_000_000_000L;

    /** 1 document YAML trong file (1 object Unity). */
    public static final class Block {
        public final int classId;
        public final long anchor;
        /**
         * Chỉ số dòng của header {@code --- !u!…} và dòng cuối (EXCLUSIVE).
         * KHÔNG final: khi {@link #setPointList} làm đổi số dòng thì toàn bộ block được
         * re-parse và các Block cũ được cập nhật TẠI CHỖ (giữ nguyên object identity)
         * để tham chiếu bên ngoài không bị hỏng. start = -1 ⇒ block không còn tồn tại.
         */
        public int start;
        public int end;
        public final String type;      // "GameObject", "Transform", "SpriteRenderer"…
        public final boolean stripped;

        Block(int classId, long anchor, int start, int end, String type, boolean stripped) {
            this.classId = classId;
            this.anchor = anchor;
            this.start = start;
            this.end = end;
            this.type = type;
            this.stripped = stripped;
        }

        @Override public String toString() {
            return "!u!" + classId + " &" + anchor + (stripped ? " stripped" : "")
                    + " [" + start + "," + end + ") " + type;
        }
    }

    private final Path file;
    private final List<String> lines;
    private final String eol;               // line ending gốc — ghi lại đúng kiểu này
    private final boolean trailingEol;      // file gốc có newline ở cuối hay không
    private List<Block> blocks = new ArrayList<>();
    private Map<Long, Block> byAnchor = new HashMap<>();
    private boolean dirty;
    private Path lastBackup;
    /** Nguồn ngẫu nhiên cho {@link #newAnchor()} — chỉ cần phân bố rộng, không cần bảo mật. */
    private final Random rnd = new Random();
    /** Anchor đã CẤP qua {@link #newAnchor()} nhưng có thể chưa kịp chèn block vào file. */
    private final Set<Long> issued = new HashSet<>();

    public PrefabDocument(Path file) throws IOException {
        this.file = file;
        byte[] raw = Files.readAllBytes(file);
        String text = new String(raw, StandardCharsets.UTF_8);
        this.eol = detectEol(text);
        List<String> ls = new ArrayList<>();
        boolean nlEnd = false;
        if (!text.isEmpty()) {
            String[] arr = EOL_SPLIT.split(text, -1);   // -1 giữ phần tử rỗng cuối
            int n = arr.length;
            if (n > 0 && arr[n - 1].isEmpty()) {        // file kết thúc bằng newline
                nlEnd = true;
                n--;
            }
            for (int i = 0; i < n; i++) ls.add(arr[i]);
        }
        this.lines = ls;
        this.trailingEol = nlEnd;
        this.blocks = scan();
        rebuildIndex();
    }

    /** CRLF nếu file có "\r\n"; CR nếu chỉ có "\r"; mặc định LF. */
    private static String detectEol(String text) {
        if (text.contains("\r\n")) return "\r\n";
        if (text.indexOf('\r') >= 0 && text.indexOf('\n') < 0) return "\r";
        return "\n";
    }

    // ==================================================================== đọc

    public Path file() { return file; }

    /** Danh sách dòng hiện tại (CHỈ ĐỌC — mọi thay đổi phải qua set*). */
    public List<String> lines() { return Collections.unmodifiableList(lines); }

    /** Toàn bộ block theo thứ tự xuất hiện. */
    public List<Block> blocks() { return Collections.unmodifiableList(blocks); }

    /** Block theo anchor (&123…). null nếu không có. */
    public Block block(long anchor) { return byAnchor.get(anchor); }

    /** Mọi block cùng classId (1=GameObject, 4=Transform, 212=SpriteRenderer…). */
    public List<Block> blocksOfClass(int classId) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) if (b.classId == classId) out.add(b);
        return out;
    }

    /**
     * Chỉ số dòng (tuyệt đối) của key trong PHẠM VI block. -1 nếu không có.
     * Ưu tiên khớp CHÍNH XÁC indent 2 (`"  " + key`) — đây là cách an toàn cho patch,
     * loại sạch key lồng ở indent 4/6 (pivot, oldSize, value…). Chỉ khi indent-2 không có
     * mới fallback sang so khớp theo trim (để đọc được key lồng như m_Bits, propertyPath).
     */
    public int findLine(Block b, String key) {
        if (b == null || key == null || b.start < 0) return -1;
        String k = norm(key);
        int from = b.start + 1, to = Math.min(b.end, lines.size());
        String exact = "  " + k;
        for (int i = from; i < to; i++) if (lines.get(i).startsWith(exact)) return i;
        for (int i = from; i < to; i++) if (lines.get(i).trim().startsWith(k)) return i;
        return -1;
    }

    /** Giá trị scalar sau dấu ":" (đã trim). null nếu không có key. */
    public String getScalar(Block b, String key) {
        int ln = findLine(b, key);
        if (ln < 0) return null;
        return valuePart(lines.get(ln), norm(key));
    }

    public int getInt(Block b, String key, int def) {
        String v = getScalar(b, key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(v.trim());
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    /** fileID trong dòng dạng {@code m_GameObject: {fileID: 123}}. 0 nếu không có. */
    public long getFileId(Block b, String key) {
        int ln = findLine(b, key);
        if (ln < 0) return 0;
        Matcher m = FILEID.matcher(lines.get(ln));
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    /** guid 32 hex trong dòng ({@code m_Sprite: {fileID: …, guid: …, type: 3}}). null nếu không có. */
    public String getGuid(Block b, String key) {
        int ln = findLine(b, key);
        if (ln < 0) return null;
        Matcher m = GUID.matcher(lines.get(ln));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Vector inline: trả {x,y} / {x,y,z} / {x,y,z,w} theo đúng số thành phần có trong dòng.
     * null nếu không tìm thấy key hoặc dòng không phải map inline.
     */
    public double[] getVec(Block b, String key) {
        int ln = findLine(b, key);
        if (ln < 0) return null;
        Map<String, String> c = parseInline(lines.get(ln), norm(key));
        if (c == null || !c.containsKey("x") || !c.containsKey("y")) return null;
        List<Double> vals = new ArrayList<>();
        vals.add(parse(c.get("x")));
        vals.add(parse(c.get("y")));
        if (c.containsKey("z")) vals.add(parse(c.get("z")));
        if (c.containsKey("w") && c.containsKey("z")) vals.add(parse(c.get("w")));
        double[] out = new double[vals.size()];
        for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
        return out;
    }

    /**
     * List điểm dạng
     * <pre>  m_Points:
     *   - {x: -50, y: 0}</pre>
     * Trả mảng rỗng khi key ghi inline "[]" hoặc không có key.
     */
    public double[][] getPointList(Block b, String key) {
        int ln = findLine(b, key);
        if (ln < 0) return new double[0][];
        String v = valuePart(lines.get(ln), norm(key));
        if (v.startsWith("[")) return new double[0][];      // "key: []"
        List<double[]> pts = new ArrayList<>();
        int to = Math.min(b.end, lines.size());
        for (int i = ln + 1; i < to; i++) {
            String t = lines.get(i).trim();
            if (!t.startsWith("- {")) break;                 // hết list
            Map<String, String> c = parseInline(t, "-");
            if (c == null || !c.containsKey("x") || !c.containsKey("y")) break;
            pts.add(new double[]{parse(c.get("x")), parse(c.get("y"))});
        }
        return pts.toArray(new double[0][]);
    }

    // ==================================================================== ghi

    /**
     * Thay guid trong dòng {@code key} của block (vd {@code skeletonDataAsset: {fileID: …, guid: …}}).
     * Chỉ đụng đúng 32 ký tự hex, {@code fileID} và {@code type} giữ nguyên — quan trọng vì
     * {@code fileID} khác nhau theo LOẠI asset (11400000 cho ScriptableObject, 2100000 cho Material).
     *
     * @return false nếu không có key đó hoặc dòng không chứa guid.
     */
    public boolean setGuid(Block b, String key, String newGuid) {
        int ln = findLine(b, key);
        return ln >= 0 && setGuidInLine(ln, newGuid);
    }

    /**
     * Như {@link #setGuid} nhưng cho phần tử ĐẦU của một list ({@code m_Materials:} rồi dòng
     * {@code - {fileID: …, guid: …}} ở dưới). Trả false nếu list rỗng/ghi inline {@code []}.
     */
    public boolean setFirstListGuid(Block b, String key, String newGuid) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        if (valuePart(lines.get(ln), norm(key)).startsWith("[")) return false;   // "key: []"
        int to = Math.min(b.end, lines.size());
        for (int i = ln + 1; i < to; i++) {
            String t = lines.get(i).trim();
            if (!t.startsWith("- ")) break;
            return setGuidInLine(i, newGuid);
        }
        return false;
    }

    /**
     * Ghi lại giá trị scalar, GIỮ NGUYÊN indent gốc. true nếu tìm thấy key.
     * Chỉ đánh dấu dirty khi nội dung dòng thực sự đổi (không normalize dòng không đổi).
     */
    public boolean setScalar(Block b, String key, String value) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String line = lines.get(ln);
        String k = norm(key);
        int c = line.indexOf(k);
        if (c < 0) return false;
        String nw = line.substring(0, c) + k + " " + (value == null ? "" : value);
        if (!nw.equals(line)) {
            lines.set(ln, nw);
            dirty = true;
        }
        return true;
    }

    /**
     * Sửa x,y của 1 vector inline, GIỮ NGUYÊN z (và w) nguyên văn như file gốc.
     * Ví dụ {@code m_LocalPosition: {x: 1, y: 2, z: 0}} → chỉ 2 số đầu bị thay.
     */
    public boolean setVec2(Block b, String key, double x, double y) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String line = lines.get(ln);
        String k = norm(key);
        int c = line.indexOf(k);
        if (c < 0) return false;
        String v = valuePart(line, k);
        if (!v.startsWith("{")) return false;                // không phải map inline
        Map<String, String> comp = parseInline(line, k);
        StringBuilder sb = new StringBuilder(line.substring(0, c)).append(k)
                .append(" {x: ").append(fmt(x)).append(", y: ").append(fmt(y));
        if (comp != null && comp.containsKey("z")) sb.append(", z: ").append(comp.get("z"));
        if (comp != null && comp.containsKey("w")) sb.append(", w: ").append(comp.get("w"));
        sb.append('}');
        String nw = sb.toString();
        if (!nw.equals(line)) {
            lines.set(ln, nw);
            dirty = true;
        }
        return true;
    }

    /**
     * Đặt quaternion từ góc xoay quanh trục Z (độ, CCW):
     * {@code {x: 0, y: 0, z: sin(a/2), w: cos(a/2)}}.
     * Nếu block có {@code m_LocalEulerAnglesHint} thì cập nhật luôn {x:0,y:0,z:angle}
     * — Unity Editor hiển thị góc theo field này, không cập nhật sẽ bị "nhảy góc".
     */
    public boolean setQuatZ(Block b, String key, double angleDeg) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String line = lines.get(ln);
        String k = norm(key);
        int c = line.indexOf(k);
        if (c < 0) return false;
        double a = normAngle(angleDeg);                       // (-180, 180] → w >= 0
        double half = Math.toRadians(a) / 2.0;
        String nw = line.substring(0, c) + k + " {x: 0, y: 0, z: " + fmt(Math.sin(half))
                + ", w: " + fmt(Math.cos(half)) + "}";
        if (!nw.equals(line)) {
            lines.set(ln, nw);
            dirty = true;
        }
        // đồng bộ hint cho Unity Editor (nếu block có field này)
        int hint = findLine(b, "m_LocalEulerAnglesHint:");
        if (hint >= 0) {
            String hl = lines.get(hint);
            int hc = hl.indexOf("m_LocalEulerAnglesHint:");
            if (hc >= 0) {
                String hn = hl.substring(0, hc) + "m_LocalEulerAnglesHint: {x: 0, y: 0, z: "
                        + fmt(a) + "}";
                if (!hn.equals(hl)) {
                    lines.set(hint, hn);
                    dirty = true;
                }
            }
        }
        return true;
    }

    /**
     * Thay guid (32 hex) NGAY TRONG 1 dòng đã biết chỉ số — dùng cho item của list
     * ({@code   - {fileID: 2100000, guid: …, type: 2}} của m_Materials) mà {@link #setScalar}
     * không với tới được vì item không có "key:". Mọi thứ khác trên dòng giữ nguyên từng byte.
     *
     * @return false nếu chỉ số dòng sai hoặc dòng không chứa guid.
     */
    public boolean setGuidInLine(int lineIndex, String newGuid) {
        if (lineIndex < 0 || lineIndex >= lines.size() || newGuid == null) return false;
        String line = lines.get(lineIndex);
        Matcher m = GUID.matcher(line);
        if (!m.find()) return false;
        String nw = line.substring(0, m.start(1)) + newGuid + line.substring(m.end(1));
        if (!nw.equals(line)) {
            lines.set(lineIndex, nw);
            dirty = true;
        }
        return true;
    }

    /**
     * Thay TOÀN BỘ list điểm của key (vd m_Points). Giữ indent gốc của key và của item.
     * List rỗng → ghi inline {@code "  key: []"} (đúng quy ước Unity, xem tài liệu §13.8).
     *
     * <p><b>Đổi số dòng:</b> khi số điểm mới khác số điểm cũ, toàn bộ file bị lệch chỉ số dòng.
     * Ở đây chọn cách AN TOÀN NHẤT: re-parse lại tất cả block từ danh sách dòng hiện tại và
     * cập nhật start/end TẠI CHỖ cho các Block cũ (giữ object identity) — mọi tham chiếu Block
     * mà UI/writer đang giữ vẫn dùng được ngay sau khi gọi.
     */
    public boolean setPointList(Block b, String key, double[][] pts) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String k = norm(key);
        String keyLine = lines.get(ln);
        int c = keyLine.indexOf(k);
        if (c < 0) return false;
        String keyIndent = keyLine.substring(0, c);

        // vùng item cũ: từ ln+1 tới dòng đầu tiên không phải "- {…}"
        int endList = ln + 1;
        int lim = Math.min(b.end, lines.size());
        while (endList < lim && lines.get(endList).trim().startsWith("- {")) endList++;
        String itemIndent = (endList > ln + 1) ? indentOf(lines.get(endList - 1)) : keyIndent;

        List<String> repl = new ArrayList<>();
        if (pts == null || pts.length == 0) {
            repl.add(keyIndent + k + " []");
        } else {
            repl.add(keyIndent + k);
            for (double[] p : pts) {
                repl.add(itemIndent + "- {x: " + fmt(p[0]) + ", y: " + fmt(p[1]) + "}");
            }
        }
        List<String> old = new ArrayList<>(lines.subList(ln, endList));
        if (old.equals(repl)) return true;                    // không đổi gì → khỏi dirty

        lines.subList(ln, endList).clear();
        lines.addAll(ln, repl);
        dirty = true;
        if (old.size() != repl.size()) reindex();             // số dòng đổi → dựng lại chỉ số
        return true;
    }

    // =========================================== chèn / xoá NGUYÊN BLOCK YAML

    /*
     * Nhóm API dưới đây phục vụ "thêm đường kẻ mới" / "xoá đường kẻ" (tài liệu 04).
     * NGUYÊN TẮC SỐNG CÒN: mọi thao tác làm ĐỔI SỐ DÒNG đều phải gọi reindex() ngay sau đó —
     * quên một lần là mọi patch tiếp theo ghi nhầm dòng của block khác (hỏng prefab im lặng).
     * reindex() cập nhật start/end của Block cũ TẠI CHỖ nên tham chiếu Block mà writer/UI
     * đang cầm vẫn dùng được ngay.
     */

    /**
     * Sinh 1 fileID (anchor) CHƯA dùng trong file này.
     *
     * <p>Quét TOÀN BỘ anchor kể cả block {@code stripped} và {@code !u!1001 PrefabInstance}
     * (danh sách {@link #blocks} chứa đủ vì {@link #BLOCK} bắt cả 2 dạng), cộng thêm các anchor
     * đã cấp ở những lần gọi trước nhưng chưa kịp chèn block — nhờ vậy 3–4 anchor của cùng
     * một node mới không tự đụng nhau.
     *
     * <p>Anchor luôn DƯƠNG và ≥ {@value #MIN_ANCHOR}. Không đụng {@code dirty} (chỉ cấp số).
     */
    public long newAnchor() {
        Set<Long> used = new HashSet<>(issued);
        for (Block b : blocks) used.add(b.anchor);
        long span = Long.MAX_VALUE - MIN_ANCHOR;
        long a;
        int guard = 0;
        do {
            a = MIN_ANCHOR + (rnd.nextLong() >>> 1) % span;
        } while (used.contains(a) && ++guard < 1000);
        issued.add(a);
        return a;
    }

    /**
     * Bản SAO các dòng thân block (đã bỏ dòng header {@code --- !u!… &…} và dòng type
     * {@code GameObject:}). Dùng để NHÂN BẢN một block đã có trong CHÍNH file đang sửa làm mẫu
     * — an toàn hơn template hằng vì luôn khớp {@code serializedVersion} của file đó.
     */
    public List<String> copyBlockBody(Block src) {
        List<String> out = new ArrayList<>();
        if (src == null || src.start < 0) return out;
        int from = src.start + 1;
        int to = Math.min(src.end, lines.size());
        if (from < to && TYPE.matcher(lines.get(from)).matches()) from++;   // bỏ dòng "GameObject:"
        for (int i = from; i < to; i++) out.add(lines.get(i));
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Chèn block mới ở CUỐI file. Xem {@link #insertBlock} cho vị trí chèn chuẩn hơn. */
    public Block appendBlock(int classId, long anchor, String type, List<String> bodyLines) {
        return insertBlock(classId, anchor, type, bodyLines, lines.size());
    }

    /**
     * Chèn 1 block mới ngay TRƯỚC dòng {@code atLine} (phải là dòng header của 1 block hoặc
     * cuối file — chèn vào giữa thân block sẽ làm hỏng file).
     *
     * @return Block vừa tạo, hoặc null nếu anchor đã tồn tại / type rỗng.
     */
    public Block insertBlock(int classId, long anchor, String type, List<String> bodyLines, int atLine) {
        if (type == null || type.trim().isEmpty()) return null;
        // anchor trùng ⇒ rebuildIndex() dùng putIfAbsent sẽ NUỐT block mới ⇒ patch sai về sau.
        if (byAnchor.containsKey(anchor)) return null;
        int at = Math.max(0, Math.min(atLine, lines.size()));
        String t = type.trim();
        List<String> blk = new ArrayList<>();
        blk.add("--- !u!" + classId + " &" + anchor);
        blk.add(t.endsWith(":") ? t : t + ":");
        if (bodyLines != null) for (String l : bodyLines) if (l != null) blk.add(l);
        lines.addAll(at, blk);
        issued.add(anchor);
        dirty = true;
        reindex();
        return byAnchor.get(anchor);
    }

    /**
     * Dòng NÊN chèn GameObject mới vào, để giữ bất biến của Unity: các block {@code !u!1}
     * không stripped có anchor TĂNG DẦN theo thứ tự xuất hiện (đúng 156/156 map), và toàn bộ
     * "vùng object riêng" nằm TRƯỚC vùng {@code !u!1001 PrefabInstance} + {@code stripped}.
     *
     * <p>Chèn ở cuối file tuy Unity vẫn đọc được nhưng rơi vào giữa vùng PrefabInstance ⇒ file
     * lệch quy ước, và lần đầu ai đó mở bằng Unity Editor rồi Ctrl+S sẽ sinh diff khổng lồ.
     */
    public int insertPointForGameObject(long goAnchor) {
        for (Block b : blocks) {
            if (b.start < 0) continue;
            // hết vùng object riêng → phải chèn TRƯỚC mốc này
            if (b.stripped || b.classId == CLS_PREFAB_INSTANCE) return b.start;
            if (b.classId == CLS_GAMEOBJECT && b.anchor > goAnchor) return b.start;
        }
        return lines.size();
    }

    /** Xoá toàn bộ dòng của block rồi dựng lại chỉ số. false nếu block null/đã bị xoá. */
    public boolean removeBlock(Block b) {
        if (b == null || b.start < 0) return false;
        int from = Math.max(0, b.start);
        int to = Math.min(b.end, lines.size());
        if (from >= to) return false;
        lines.subList(from, to).clear();
        dirty = true;
        reindex();
        return true;
    }

    /**
     * Thêm {@code  - {fileID: N}} vào {@code m_Children} của Transform CHA.
     * Xử lý cả trường hợp đang là {@code m_Children: []} (1 dòng → 2 dòng, tài liệu 04 §3).
     * Item mới append vào CUỐI danh sách (Unity dùng thứ tự này làm sibling order).
     * Đã có sẵn anchor đó → trả true mà không sửa gì.
     */
    public boolean addChildRef(Block parentTransform, long childTrAnchor) {
        return addListRef(parentTransform, "m_Children:", childTrAnchor, "");
    }

    /**
     * Thêm {@code  - component: {fileID: N}} vào {@code m_Component} của GameObject — dùng khi
     * gắn thêm component vào node ĐÃ CÓ (vd bật PlatformEffector2D cho đường kẻ đang là đường đất).
     * Item của m_Component có tiền tố {@code component: } chứ không trần như m_Children.
     */
    public boolean addComponentRef(Block gameObject, long compAnchor) {
        return addListRef(gameObject, "m_Component:", compAnchor, "component: ");
    }

    /** Gỡ 1 component khỏi {@code m_Component} của GameObject (không xoá block của nó). */
    public boolean removeComponentRef(Block gameObject, long compAnchor) {
        return removeListRef(gameObject, "m_Component:", compAnchor);
    }

    /** Số thực của key (float trong YAML Unity); {@code def} nếu thiếu key hoặc không parse được. */
    public double getDouble(Block b, String key, double def) {
        String s = getScalar(b, key);
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Xoá dòng {@code  - {fileID: N}} khỏi {@code m_Children}. Nếu hết con thì viết lại đúng
     * {@code m_Children: []} (để trống sẽ thành YAML null ⇒ Unity ghi lại thành [] → diff bẩn).
     */
    public boolean removeChildRef(Block parentTransform, long childTrAnchor) {
        return removeListRef(parentTransform, "m_Children:", childTrAnchor);
    }

    /**
     * Mọi block có tham chiếu {@code fileID: <anchor>} NỘI FILE (bỏ qua dòng kèm {@code guid:}
     * vì đó là namespace của file khác). Dùng để biết một node có đang bị MonoBehaviour
     * (MapManager: _edgeColTop/_layersBG…) hay {@code m_TransformParent} của PrefabInstance
     * giữ hay không TRƯỚC khi cho phép xoá — kiểm tra THẬT, không đoán theo tên.
     */
    public List<Block> blocksReferencing(long anchor) {
        List<Block> out = new ArrayList<>();
        if (anchor == 0) return out;
        String needle = String.valueOf(anchor);
        for (Block b : blocks) {
            if (b.start < 0) continue;
            int to = Math.min(b.end, lines.size());
            boolean hit = false;
            for (int i = b.start; i < to && !hit; i++) {
                String l = lines.get(i);
                if (l.indexOf(needle) < 0) continue;         // lọc nhanh, tránh regex vô ích
                if (l.contains("guid:")) continue;           // tham chiếu sang asset khác
                Matcher m = FILEID.matcher(l);
                while (m.find()) {
                    if (needle.equals(m.group(1))) { hit = true; break; }
                }
            }
            if (hit) out.add(b);
        }
        return out;
    }

    /**
     * Mọi block component thuộc 1 GameObject ({@code m_GameObject: {fileID: goAnchor}}) —
     * Transform, collider, effector, MonoBehaviour… Dùng khi XOÁ node để không sót block nào
     * (có node layer 6 kèm cả !u!61/!u!212/!u!114, không chỉ 3 block cứng).
     */
    public List<Block> blocksOfGameObject(long goAnchor) {
        List<Block> out = new ArrayList<>();
        if (goAnchor == 0) return out;
        for (Block b : blocks) {
            if (b.start < 0 || b.stripped || b.anchor == goAnchor) continue;
            if (getFileId(b, "m_GameObject:") == goAnchor) out.add(b);
        }
        return out;
    }

    // ── nội bộ: list "- {fileID: N}" nhiều dòng ──

    private boolean addListRef(Block b, String key, long anchor, String itemPrefix) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String k = norm(key);
        String line = lines.get(ln);
        int c = line.indexOf(k);
        if (c < 0) return false;
        String indent = line.substring(0, c);
        String item = indent + "- " + itemPrefix + "{fileID: " + anchor + "}";
        if (valuePart(line, k).startsWith("[")) {           // "m_Children: []" → 2 dòng
            lines.set(ln, indent + k);
            lines.add(ln + 1, item);
        } else {
            int i = ln + 1, lim = Math.min(b.end, lines.size());
            while (i < lim && lines.get(i).trim().startsWith("- ")) {
                if (fileIdOf(lines.get(i)) == anchor) return true;   // đã có rồi
                i++;
            }
            lines.add(i, item);
        }
        dirty = true;
        reindex();
        return true;
    }

    private boolean removeListRef(Block b, String key, long anchor) {
        int ln = findLine(b, key);
        if (ln < 0) return false;
        String k = norm(key);
        String line = lines.get(ln);
        int c = line.indexOf(k);
        if (c < 0) return false;
        if (valuePart(line, k).startsWith("[")) return false;        // list đang rỗng
        String indent = line.substring(0, c);
        int hit = -1, cnt = 0;
        int i = ln + 1, lim = Math.min(b.end, lines.size());
        while (i < lim && lines.get(i).trim().startsWith("- ")) {
            if (hit < 0 && fileIdOf(lines.get(i)) == anchor) hit = i;
            cnt++;
            i++;
        }
        if (hit < 0) return false;
        lines.remove(hit);
        if (cnt == 1) lines.set(ln, indent + k + " []");             // hết con → về dạng "[]"
        dirty = true;
        reindex();
        return true;
    }

    /** fileID đầu tiên trong dòng (0 nếu không có). */
    private static long fileIdOf(String line) {
        Matcher m = FILEID.matcher(line);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    /** true nếu đã có ít nhất 1 dòng thực sự bị thay đổi. */
    public boolean dirty() { return dirty; }

    /** Đường dẫn file .bak của lần save() gần nhất (null nếu chưa save). */
    public Path lastBackup() { return lastBackup; }

    /**
     * Backup file gốc sang {@code backupDir/<tên file>.<yyyyMMdd-HHmmss>.bak} rồi GHI ĐÈ file gốc.
     * Ghi qua file tạm cùng thư mục + move (atomic nếu OS hỗ trợ) để không hỏng file khi crash.
     */
    public void save(Path backupDir) throws IOException {
        if (!dirty) {
            System.out.println("[PrefabDocument] save() nhưng không có thay đổi nào: " + file);
        }
        if (backupDir != null) {
            Files.createDirectories(backupDir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path bak = backupDir.resolve(file.getFileName().toString() + "." + stamp + ".bak");
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            lastBackup = bak;
            System.out.println("[PrefabDocument] backup → " + bak.toAbsolutePath());
        }
        byte[] data = bytes();
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = (dir != null ? dir : Path.of(".")).resolve(file.getFileName() + ".tmp-" + System.currentTimeMillis());
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    /** Ghi ra file KHÁC (không backup, không đụng file gốc) — dùng cho self-test. */
    public void saveAs(Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.write(target, bytes());
    }

    /** Nội dung file hiện tại (UTF-8, giữ nguyên line ending + newline cuối của file gốc). */
    public byte[] bytes() {
        StringBuilder sb = new StringBuilder(lines.size() * 40);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(eol);
            sb.append(lines.get(i));
        }
        if (trailingEol && !lines.isEmpty()) sb.append(eol);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ============================================================ tiện ích số

    /**
     * Format số kiểu Unity: round-trip ngắn nhất của FLOAT, không E-notation, bỏ ".0" thừa,
     * -0.0 → "0". (Đã verify 12 437/12 437 literal thập phân lấy từ 156 prefab map khớp byte.)
     */
    public static String fmt(double v) {
        float f = (float) v;
        if (f == 0f || Float.isNaN(f) || Float.isInfinite(f)) return "0";
        String s = Float.toString(f);
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) {
            s = new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
        }
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    // ============================================================ nội bộ

    /** Quét lại toàn bộ block từ danh sách dòng hiện tại. */
    private List<Block> scan() {
        List<Block> out = new ArrayList<>();
        Block open = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = BLOCK.matcher(lines.get(i));
            if (!m.matches()) continue;
            if (open != null) open.end = i;
            int classId;
            long anchor;
            try {
                classId = Integer.parseInt(m.group(1));
                anchor = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                continue;                                    // classId lạ quá lớn → bỏ qua
            }
            String type = "";
            if (i + 1 < lines.size()) {
                Matcher tm = TYPE.matcher(lines.get(i + 1));
                if (tm.matches()) type = tm.group(1);
            }
            open = new Block(classId, anchor, i, lines.size(), type, m.group(3) != null);
            out.add(open);
        }
        return out;
    }

    private void rebuildIndex() {
        Map<Long, Block> idx = new HashMap<>();
        for (Block b : blocks) idx.putIfAbsent(b.anchor, b);
        byAnchor = idx;
    }

    /**
     * Re-parse sau khi số dòng đổi. Block cũ được cập nhật start/end TẠI CHỖ để tham chiếu
     * bên ngoài không hỏng; block biến mất bị đánh dấu start = end = -1.
     */
    private void reindex() {
        List<Block> fresh = scan();
        List<Block> merged = new ArrayList<>(fresh.size());
        Set<Long> alive = new HashSet<>();
        for (Block nb : fresh) {
            alive.add(nb.anchor);
            Block old = byAnchor.get(nb.anchor);
            if (old != null && old.classId == nb.classId) {
                old.start = nb.start;
                old.end = nb.end;
                merged.add(old);
            } else {
                merged.add(nb);
            }
        }
        for (Block b : blocks) {
            if (!alive.contains(b.anchor)) { b.start = -1; b.end = -1; }
        }
        blocks = merged;
        rebuildIndex();
    }

    /** Thêm ":" nếu caller quên. */
    private static String norm(String key) {
        String k = key.trim();
        return k.endsWith(":") ? k : k + ":";
    }

    /** Phần sau "key:" của dòng (đã trim). */
    private static String valuePart(String line, String k) {
        int c = line.indexOf(k);
        if (c < 0) return line.trim();
        return line.substring(c + k.length()).trim();
    }

    private static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return line.substring(0, i);
    }

    /**
     * Bóc các thành phần số của map inline sau key: {x: 1, y: 2, z: 0} → {x=1, y=2, z=0}
     * (giữ NGUYÊN VĂN chuỗi số để có thể ghi lại z/w không đổi).
     */
    private static Map<String, String> parseInline(String line, String k) {
        String v = valuePart(line, k);
        int lb = v.indexOf('{');
        if (lb >= 0) v = v.substring(lb + 1);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = COMP.matcher(v);
        while (m.find()) out.putIfAbsent(m.group(1), m.group(2));
        return out;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /** Đưa góc về (-180, 180] để quaternion có w >= 0 giống Unity. */
    private static double normAngle(double deg) {
        double a = deg % 360.0;
        if (a > 180.0) a -= 360.0;
        if (a <= -180.0) a += 360.0;
        return a;
    }
}
