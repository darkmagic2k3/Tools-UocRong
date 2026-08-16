package com.apex.maptool.unity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đối chiếu bản Java với <b>chính tool Python đang dùng</b> ({@code tools/honma-composer/
 * hao_quang_export.py}) — chạy hàm Python thật rồi so từng ký tự.
 *
 * <p>Vì sao cần: yêu cầu là "viết lại chức năng Y HỆT". So với file {@code .anim} có sẵn trong
 * client KHÔNG chứng minh được điều đó — mấy file ấy do <b>Unity</b> ghi (số float32 kiểu
 * {@code 0.06666667}, CRLF), khác cả bản Python. Bản Python mới là mốc đúng.
 *
 * <p>Python không có trên máy (hoặc thiếu thư mục tool) thì {@link #available()} trả false và phép
 * kiểm bị BỎ QUA có báo cáo — không âm thầm coi là đạt.
 */
public final class HaoQuangOracle {

    private final Path python;
    private final Path driver;
    private final Path pyModule;

    private HaoQuangOracle(Path python, Path driver, Path pyModule) {
        this.python = python;
        this.driver = driver;
        this.pyModule = pyModule;
    }

    /** null nếu không dựng được (thiếu python / thiếu hao_quang_export.py). */
    public static HaoQuangOracle create(Path toolsHonmaDir) {
        if (toolsHonmaDir == null) return null;
        Path mod = toolsHonmaDir.resolve("hao_quang_export.py");
        if (!Files.isRegularFile(mod)) return null;
        Path py = which("python");
        if (py == null) py = which("python3");
        if (py == null) return null;
        try {
            Path d = Files.createTempFile("hqoracle-", ".py");
            Files.write(d, DRIVER.getBytes(StandardCharsets.UTF_8));
            return new HaoQuangOracle(py, d, mod);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean available() { return true; }

    private static Path which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String ext : new String[]{"", ".exe", ".bat", ".cmd"}) {
                Path p = Path.of(dir).resolve(exe + ext);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
            }
        }
        return null;
    }

    /** Gọi 1 hàm của bản Python, trả về text kết quả (null = chạy lỗi). */
    public String call(JsonObject payload) {
        try {
            ProcessBuilder pb = new ProcessBuilder(python.toString(), driver.toString(), pyModule.toString());
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(new Gson().toJson(payload).getBytes(StandardCharsets.UTF_8));
            }
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("[hqoracle] python exit " + code + ": "
                        + new String(err, StandardCharsets.UTF_8).trim());
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[hqoracle] lỗi gọi python: " + e);
            return null;
        }
    }

    /**
     * Che 2 fileID NGẪU NHIÊN mà {@code add_param_state_transition} sinh ra, để so được hai bản.
     * Chỉ che số {@code &<id>} có trong {@code out} mà KHÔNG có trong {@code in} — fileID cũ giữ nguyên
     * nên vẫn phát hiện được nếu bản Java lỡ đụng vào chúng.
     */
    public static String maskNewIds(String in, String out) {
        List<String> fresh = new ArrayList<>();
        Matcher m = Pattern.compile("&(\\d+)").matcher(out);
        while (m.find()) {
            String id = m.group(1);
            if (!in.contains("&" + id) && !fresh.contains(id)) fresh.add(id);
        }
        String r = out;
        for (int i = 0; i < fresh.size(); i++) {
            r = r.replace(fresh.get(i), "%NEWID" + i + "%");
        }
        return r;
    }

    /** Che spriteID/guid ngẫu nhiên (32 hex) không có trong bản gốc. */
    public static String maskNewGuids(String in, String out) {
        List<String> fresh = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([0-9a-fA-F]{32})\\b").matcher(out);
        while (m.find()) {
            String g = m.group(1);
            if (!in.contains(g) && !fresh.contains(g)) fresh.add(g);
        }
        String r = out;
        for (int i = 0; i < fresh.size(); i++) r = r.replace(fresh.get(i), "%NEWGUID" + i + "%");
        return r;
    }

    /** Vị trí ký tự đầu tiên khác nhau + trích đoạn quanh đó (−1 = giống hệt). */
    public static String firstDiff(String a, String b) {
        if (a.equals(b)) return null;
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        String ca = a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 40)).replace("\n", "\\n");
        String cb = b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 40)).replace("\n", "\\n");
        return "lệch ở ký tự " + i + " (dài " + a.length() + " vs " + b.length() + ")"
                + "\n      java: …" + ca + "…"
                + "\n      py  : …" + cb + "…";
    }

    /** Script trung gian: nạp hao_quang_export.py rồi gọi đúng 1 hàm theo lệnh JSON ở stdin. */
    private static final String DRIVER = """
            import sys, json, importlib.util, pathlib
            spec = importlib.util.spec_from_file_location("hqmod", pathlib.Path(sys.argv[1]))
            hq = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(hq)
            p = json.loads(sys.stdin.buffer.read().decode("utf-8"))
            op = p["op"]
            if op == "anim":
                out = hq.build_anim_clip_yaml(p["name"], p["guids"], interval=p["interval"],
                                              sample_rate=p["rate"], loop=p["loop"])
            elif op == "animmeta":
                out = hq.build_anim_meta_yaml(p["guid"])
            elif op == "enum":
                t, v = hq.add_enum_value(p["text"], p["name"], enum_name=p["enum"])
                out = json.dumps({"text": t, "value": v})
            elif op == "case":
                out = hq.add_switch_case_dual(p["text"], p["enum"], p["tb"], p["tf"],
                                              method_name=p["method"], animator_var=p["var"],
                                              enum_type=p["type"])
            elif op == "upsert":
                t, how = hq.upsert_switch_case_dual(p["text"], p["enum"], p["tb"], p["tf"],
                                                    method_name=p["method"], animator_var=p["var"],
                                                    enum_type=p["type"])
                out = json.dumps({"text": t, "how": how})
            elif op == "ppu":
                out = hq.set_sprite_ppu(p["text"], p["ppu"])
            elif op == "pivot":
                out = hq.set_sprite_pivot(p["text"], p["x"], p["y"])
            elif op == "sorting":
                t, old = hq.set_gameobject_sprite_sorting(p["text"], p["go"], p["order"])
                out = json.dumps({"text": t, "old": old})
            elif op == "retarget":
                out = hq.retarget_meta(p["text"], p["guid"])
            elif op == "controller":
                out = hq.add_param_state_transition(p["text"], p["trigger"], p["state"], p["guid"])
            elif op == "fmt":
                out = json.dumps([hq._fmt(v) for v in p["values"]])
            else:
                raise SystemExit("op la: " + op)
            sys.stdout.buffer.write(out.encode("utf-8"))
            """;
}
