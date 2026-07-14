package com.apex.maptool.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tên buff (ItemAttribute) đọc từ source server: NAME("mô tả", id).
 * Server đổi enum → tool tự cập nhật theo (không hardcode).
 */
public final class AttrNames {

    private static final Pattern ENTRY = Pattern.compile("\\(\\s*\"([^\"]+)\"\\s*,\\s*(\\d+)\\s*\\)");

    private final Map<Integer, String> names = new HashMap<>();

    /** Parse {serverRepo}/src/main/java/game/enums/ItemAttribute.java. Lỗi → map rỗng (hiện số). */
    public AttrNames(Path serverRepo) {
        Path f = serverRepo.resolve("src/main/java/game/enums/ItemAttribute.java");
        try {
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    Matcher m = ENTRY.matcher(line);
                    if (m.find()) names.put(Integer.parseInt(m.group(2)), m.group(1));
                }
                System.out.println("[AttrNames] loaded " + names.size() + " buff names từ " + f);
            } else {
                System.err.println("[AttrNames] không thấy " + f + " → hiện số thay tên");
            }
        } catch (Exception e) {
            System.err.println("[AttrNames] parse fail: " + e.getMessage());
        }
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

    /** Tất cả buff (id, desc) sort theo id — cho picker. */
    public java.util.List<java.util.Map.Entry<Integer, String>> entries() {
        return names.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toList());
    }
}
