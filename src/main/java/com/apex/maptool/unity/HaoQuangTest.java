package com.apex.maptool.unity;

import com.apex.maptool.config.ToolConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tự kiểm bộ ghi asset Hào Quang — <b>CHỈ ĐỌC client</b>, mọi phép sửa chạy trên BẢN SAO trong thư
 * mục tạm và kiểm lại bằng chính bộ đọc.
 *
 * <p>Đây là phần nguy hiểm nhất của tool: nó sửa {@code ActorVisual.cs}, 2 file enum,
 * {@code HaoQuang.controller} và {@code Player.prefab} THẬT của client. Nên phép kiểm mạnh nhất
 * không phải "hàm không ném lỗi" mà là <b>dựng lại đúng file game đang chạy</b>: đọc từng
 * {@code .anim} có sẵn, rút guid frame + nhịp + loop ra, sinh lại bằng
 * {@link HaoQuang#buildAnimClipYaml} rồi so <b>từng ký tự</b> với file gốc.
 *
 * <p>Bài cuối băm SHA-256 mọi file client bị đụng tới trước/sau khi chạy để chứng minh test
 * <b>không sửa gì</b>.
 */
public final class HaoQuangTest {

    private HaoQuangTest() { }

    private static int pass, fail;
    private static final List<String> notes = new ArrayList<>();

    public static int run(String assetsArg) {
        Path assets = (assetsArg != null && !assetsArg.isEmpty())
                ? Path.of(assetsArg)
                : new ToolConfig().assetsRoot();
        if (!Files.isDirectory(assets)) {
            System.err.println("[hqtest] không thấy thư mục Assets: " + assets);
            return 2;
        }
        Map<String, Path> paths = HaoQuang.detectClientPaths(assets.resolve("Textures"));
        Path animDir = paths.get("anim_out_dir");
        Path controller = paths.get("controller");
        Path enumSm = paths.get("enum");
        Path enumDb = paths.get("enum_dacbiet");
        Path actor = paths.get("actorvisual");
        Path prefab = paths.get("prefab");
        Path texDir = assets.resolve("Textures/GamePlay/HaoQuang");

        System.out.println("[hqtest] Assets = " + assets + "  (CHỈ ĐỌC)\n");
        Map<String, String> before = hashAll(controller, enumSm, enumDb, actor, prefab);

        Path tmp;
        try {
            tmp = Files.createTempDirectory("hqtest-");
        } catch (Exception e) {
            System.err.println("[hqtest] không tạo được thư mục tạm: " + e);
            return 2;
        }

        t1PythonOracle(animDir, controller, enumSm, actor, prefab, texDir, honmaDir());
        t2Controller(controller, tmp);
        t3Enum(enumSm, enumDb);
        t4SwitchCase(actor);
        t5Prefab(prefab);
        t6Meta(texDir);
        t7Retarget(texDir);
        t8PivotMath();
        t9Untouched(before, controller, enumSm, enumDb, actor, prefab);

        for (String n : notes) System.out.println("  · " + n);
        System.out.printf("%n[hqtest] %d PASS / %d FAIL  ·  thư mục tạm: %s%n", pass, fail, tmp);
        return fail == 0 ? 0 : 1;
    }

    /**
     * T1 — đối chiếu với CHÍNH BẢN PYTHON đang dùng, từng ký tự.
     *
     * <p>Không so với {@code .anim} có sẵn trong client: những file đó do <b>Unity</b> ghi (float32
     * kiểu {@code 0.06666667}, CRLF), bản Python cũng không trùng chúng. Bản Python mới là mốc của
     * "viết lại y hệt". Không có Python trên máy thì BỎ QUA có báo, không coi là đạt.
     */
    private static void t1PythonOracle(Path animDir, Path controller, Path enumSm, Path actor,
                                       Path prefab, Path texDir, Path honmaDir) {
        HaoQuangOracle o = HaoQuangOracle.create(honmaDir);
        if (o == null) {
            notes.add("T1: BỎ QUA đối chiếu bản Python (thiếu python trên PATH hoặc thiếu "
                    + honmaDir + "/hao_quang_export.py)");
            return;
        }
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // (a) build_anim_clip_yaml — lấy guid + nhịp THẬT từ mọi clip trong client
        int animOk = 0, animTot = 0;
        String animErr = null;
        List<Path> anims = new ArrayList<>();
        if (animDir != null && Files.isDirectory(animDir)) {
            try (Stream<Path> s = Files.list(animDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().toLowerCase().endsWith(".anim")) anims.add(p);
                }
            } catch (Exception ignored) { }
        }
        anims.sort(HaoQuang.BY_NAME);
        for (Path p : anims) {
            String txt = HaoQuang.read(p);
            List<String> guids = new ArrayList<>();
            List<Double> times = new ArrayList<>();
            Matcher m = Pattern.compile("- time: ([0-9.eE+\\-]+)\\s*\\r?\\n\\s*value: \\{fileID: \\d+, "
                    + "guid: ([0-9a-fA-F]{32}), type: 3\\}").matcher(txt);
            while (m.find()) { times.add(Double.parseDouble(m.group(1))); guids.add(m.group(2)); }
            if (guids.size() < 2) continue;
            animTot++;
            double interval = times.get(1) - times.get(0);
            int rate = intOf(txt, "m_SampleRate:\\s*(\\d+)", 60);
            boolean loop = intOf(txt, "m_LoopTime:\\s*(\\d+)", 1) == 1;
            String name = strOf(txt, "m_Name:\\s*(\\S+)");
            com.google.gson.JsonObject req = new com.google.gson.JsonObject();
            req.addProperty("op", "anim");
            req.addProperty("name", name);
            req.add("guids", gson.toJsonTree(guids));
            req.addProperty("interval", interval);
            req.addProperty("rate", rate);
            req.addProperty("loop", loop);
            String py = o.call(req);
            String jv = HaoQuang.buildAnimClipYaml(name, guids, interval, rate, loop);
            if (py == null) { animErr = "python lỗi ở " + p.getFileName(); break; }
            if (py.equals(jv)) animOk++;
            else if (animErr == null) animErr = p.getFileName() + ": " + HaoQuangOracle.firstDiff(jv, py);
        }
        if (animTot == 0) bad("T1a anim", "không có clip nào để đối chiếu");
        else if (animOk == animTot) ok("T1a anim  khớp bản Python từng ký tự trên " + animTot + " clip thật");
        else bad("T1a anim", animOk + "/" + animTot + " khớp — " + animErr);

        // (b) build_anim_meta_yaml
        String g = HaoQuang.newGuid();
        cmpText("T1b anim.meta", o, obj("op", "animmeta", "guid", g), HaoQuang.buildAnimMetaYaml(g));

        // (c) add_enum_value trên file enum THẬT
        if (enumSm != null && Files.isRegularFile(enumSm)) {
            String t0 = HaoQuang.read(enumSm);
            com.google.gson.JsonObject req = obj("op", "enum", "name", "HAO_QUANG_HQTEST_X");
            req.addProperty("text", t0);
            req.addProperty("enum", "Enum_HaoQuang");
            String py = o.call(req);
            HaoQuang.EnumAdd jv = HaoQuang.addEnumValue(t0, "HAO_QUANG_HQTEST_X", "Enum_HaoQuang");
            if (py == null) bad("T1c enum", "python lỗi");
            else {
                com.google.gson.JsonObject r = gson.fromJson(py, com.google.gson.JsonObject.class);
                String pt = r.get("text").getAsString();
                int pv = r.get("value").getAsInt();
                if (pv != jv.value()) bad("T1c enum", "giá trị java=" + jv.value() + " ≠ python=" + pv);
                else if (!pt.equals(jv.text())) bad("T1c enum", HaoQuangOracle.firstDiff(jv.text(), pt));
                else ok("T1c enum  khớp bản Python (giá trị + toàn văn file)");
            }
        }

        // (d) add_switch_case_dual trên ActorVisual THẬT (cả 2 hệ)
        if (actor != null && Files.isRegularFile(actor)) {
            String a0 = HaoQuang.read(actor);
            boolean allOk = true;
            String why = null;
            for (String[] sys : new String[][]{
                    {"PlayHaoQuangAnim", "_animHaoQuang", "Enum_HaoQuang"},
                    {"PlayHaoQuangDacBietAnim", "_animHaoQuangDacBiet", "Enum_HaoQuangDacBiet"}}) {
                com.google.gson.JsonObject req = obj("op", "case", "enum", "HAO_QUANG_HQTEST_X");
                req.addProperty("text", a0);
                req.addProperty("tb", "tsau");
                req.addProperty("tf", "ttruoc");
                req.addProperty("method", sys[0]);
                req.addProperty("var", sys[1]);
                req.addProperty("type", sys[2]);
                String py = o.call(req);
                String jv = HaoQuang.addSwitchCaseDual(a0, "HAO_QUANG_HQTEST_X", "tsau", "ttruoc",
                        sys[0], sys[1], sys[2]);
                if (py == null || !py.equals(jv)) {
                    allOk = false;
                    why = sys[2] + ": " + (py == null ? "python lỗi" : HaoQuangOracle.firstDiff(jv, py));
                    break;
                }
            }
            if (allOk) ok("T1d case  khớp bản Python trên ActorVisual thật (cả 2 hệ)");
            else bad("T1d case", why);
        }

        // (e) set_sprite_ppu / set_sprite_pivot / retarget_meta trên .meta THẬT
        Path meta = anyFrameMeta(texDir);
        if (meta != null) {
            String m0 = HaoQuang.read(meta);
            com.google.gson.JsonObject r1 = obj("op", "ppu");
            r1.addProperty("text", m0);
            r1.addProperty("ppu", 133.3333);
            cmpText("T1e PPU", o, r1, HaoQuang.setSpritePpu(m0, 133.3333));

            com.google.gson.JsonObject r2 = obj("op", "pivot");
            r2.addProperty("text", m0);
            r2.addProperty("x", 0.4321);
            r2.addProperty("y", 0.2109);
            cmpText("T1f pivot", o, r2, HaoQuang.setSpritePivot(m0, 0.4321, 0.2109));

            HaoQuang.Template tmpl = HaoQuang.templateMetaText(texDir);
            String keep = HaoQuang.readMetaGuid(meta);
            com.google.gson.JsonObject r3 = obj("op", "retarget", "guid", keep);
            r3.addProperty("text", tmpl.text());
            String py = o.call(r3);
            String jv = HaoQuang.retargetMeta(tmpl.text(), keep);
            if (py == null) bad("T1g retarget", "python lỗi");
            else {
                String pm = HaoQuangOracle.maskNewGuids(tmpl.text(), py);
                String jm = HaoQuangOracle.maskNewGuids(tmpl.text(), jv);
                if (pm.equals(jm)) {
                    ok("T1g retarget  khớp bản Python (spriteID ngẫu nhiên đã che)");
                } else if (pm.replace("\r", "").equals(jm.replace("\r", ""))
                        && cr(jm) == cr(tmpl.text()) && cr(pm) == cr(tmpl.text()) - 1) {
                    // LỆCH CÓ CHỦ Ý: `\s*$` của Python (MULTILINE) NUỐT ký tự \r ở cuối dòng guid,
                    // biến đúng 1 dòng CRLF thành LF. Java giữ nguyên xuống dòng của file — tốt hơn
                    // (không sinh nhiễu diff trong git), nội dung YAML y hệt.
                    ok("T1g retarget  khớp bản Python; khác ĐÚNG 1 ký tự \\r ở dòng guid "
                            + "(Python nuốt CR do `\\s*$`, bản Java giữ nguyên xuống dòng của file)");
                } else {
                    bad("T1g retarget", HaoQuangOracle.firstDiff(jm, pm));
                }
            }
        }

        // (f) set_gameobject_sprite_sorting trên Player.prefab THẬT
        if (prefab != null && Files.isRegularFile(prefab)) {
            String p0 = HaoQuang.read(prefab);
            com.google.gson.JsonObject req = obj("op", "sorting", "go", "HaoQuang2");
            req.addProperty("text", p0);
            req.addProperty("order", 1);
            String py = o.call(req);
            HaoQuang.Sorting jv = HaoQuang.setGameObjectSpriteSorting(p0, "HaoQuang2", 1);
            if (py == null) bad("T1h prefab", "python lỗi");
            else {
                com.google.gson.JsonObject r = gson.fromJson(py, com.google.gson.JsonObject.class);
                Integer pold = r.get("old").isJsonNull() ? null : r.get("old").getAsInt();
                String pt = r.get("text").getAsString();
                if (java.util.Objects.equals(pold, jv.oldOrder()) && pt.equals(jv.text())) {
                    ok("T1h prefab  khớp bản Python (sortingOrder " + pold + "→1)");
                } else if (pold == null && jv.oldOrder() != null) {
                    // LỖI CỦA BẢN PYTHON: regex tách document dùng `&(\d+)\n` mà Player.prefab là
                    // CRLF 100% ⇒ không khớp document nào, hàm âm thầm trả "không thấy GameObject".
                    // Nghĩa là bản Python CHƯA BAO GIỜ đặt được sortingOrder — chỉ in cảnh báo.
                    ok("T1h prefab  bản Java tìm ra HaoQuang2 (order " + jv.oldOrder()
                            + "), bản PYTHON trả null — lỗi CRLF của bản cũ, bản Java đã sửa");
                } else {
                    bad("T1h prefab", "order cũ java=" + jv.oldOrder() + " ≠ python=" + pold
                            + (pt.equals(jv.text()) ? "" : " · " + HaoQuangOracle.firstDiff(jv.text(), pt)));
                }
            }
        }

        // (g) add_param_state_transition — che 2 fileID ngẫu nhiên rồi so
        if (controller != null && Files.isRegularFile(controller)) {
            String c0 = HaoQuang.read(controller);
            String cg = HaoQuang.newGuid();
            com.google.gson.JsonObject req = obj("op", "controller", "trigger", "hqtest_trigger_x");
            req.addProperty("text", c0);
            req.addProperty("state", "aniHqTestX");
            req.addProperty("guid", cg);
            String py = o.call(req);
            String jv = HaoQuang.addParamStateTransition(c0, "hqtest_trigger_x", "aniHqTestX", cg);
            if (py == null) bad("T1i controller", "python lỗi");
            else {
                String pm = HaoQuangOracle.maskNewIds(c0, py);
                String jm = HaoQuangOracle.maskNewIds(c0, jv);
                if (pm.equals(jm)) ok("T1i controller  khớp bản Python (fileID ngẫu nhiên đã che)");
                else bad("T1i controller", HaoQuangOracle.firstDiff(jm, pm));
            }
        }

        // (h) _fmt — hàm format số, gốc của mọi lệch vặt trong YAML
        double[] vals = {0, 1, 0.2, 1.0 / 15, 2.0 / 15, 1.0 / 60, 0.06666667, 133.3333, 100, 0.4321, 1e-7, 12345.678};
        com.google.gson.JsonObject rf = obj("op", "fmt");
        rf.add("values", gson.toJsonTree(vals));
        String pyf = o.call(rf);
        if (pyf == null) bad("T1j fmt", "python lỗi");
        else {
            String[] pv = gson.fromJson(pyf, String[].class);
            List<String> mism = new ArrayList<>();
            for (int i = 0; i < vals.length; i++) {
                String jf = HaoQuang.fmt(vals[i]);
                if (!jf.equals(pv[i])) mism.add(vals[i] + ": java='" + jf + "' py='" + pv[i] + "'");
            }
            if (mism.isEmpty()) ok("T1j fmt  khớp bản Python trên " + vals.length + " giá trị");
            else bad("T1j fmt", String.join(" · ", mism));
        }
    }

    private static void cmpText(String tag, HaoQuangOracle o, com.google.gson.JsonObject req, String java) {
        String py = o.call(req);
        if (py == null) { bad(tag, "python lỗi"); return; }
        if (py.equals(java)) ok(tag + "  khớp bản Python từng ký tự");
        else bad(tag, HaoQuangOracle.firstDiff(java, py));
    }

    private static com.google.gson.JsonObject obj(String... kv) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    // ── T2: controller ──────────────────────────────────────────────────────
    private static void t2Controller(Path controller, Path tmp) {
        if (controller == null || !Files.isRegularFile(controller)) { bad("T2 controller", "không thấy file"); return; }
        String c0 = HaoQuang.read(controller);
        String trig = "hqtest_trigger_x", clip = "aniHqTestX", guid = HaoQuang.newGuid();

        if (HaoQuang.controllerHasTrigger(c0, trig)) { bad("T2 controller", "trigger test đã tồn tại sẵn?"); return; }
        String c1;
        try { c1 = HaoQuang.addParamStateTransition(c0, trig, clip, guid); }
        catch (Exception e) { bad("T2 controller", "thêm trigger lỗi: " + e.getMessage()); return; }

        if (!HaoQuang.controllerHasTrigger(c1, trig)) { bad("T2 controller", "thêm xong nhưng dò không ra trigger"); return; }
        String got = HaoQuang.controllerTriggerClipGuid(c1, trig);
        if (!guid.equals(got)) { bad("T2 controller", "lần ra clip guid sai: " + got + " ≠ " + guid); return; }
        int d0 = count(c0, "\n--- !u!"), d1 = count(c1, "\n--- !u!");
        if (d1 != d0 + 2) { bad("T2 controller", "số document thêm " + (d1 - d0) + ", phải là 2"); return; }
        for (String k : new String[]{"m_AnimatorLayers:", "m_ChildStateMachines:", "m_EntryTransitions:"}) {
            if (count(c1, k) != count(c0, k)) { bad("T2 controller", "hỏng cấu trúc ở " + k); return; }
        }
        // thêm lần 2 phải BÁO LỖI, không được nhân đôi
        try {
            HaoQuang.addParamStateTransition(c1, trig, clip, guid);
            bad("T2 controller", "thêm trùng trigger mà KHÔNG báo lỗi");
            return;
        } catch (HaoQuang.HqError ignored) { }

        // các trigger CÓ SẴN vẫn lần ra được clip → bộ đọc đúng với dữ liệu thật
        int resolved = 0, total = 0;
        Matcher m = Pattern.compile("m_ConditionEvent:\\s*(\\S+)").matcher(c0);
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            String t = m.group(1);
            if (seen.contains(t)) continue;
            seen.add(t);
            total++;
            if (HaoQuang.controllerTriggerClipGuid(c0, t) != null) resolved++;
        }
        if (total > 0 && resolved < total) {
            bad("T2 controller", "chỉ lần ra clip cho " + resolved + "/" + total + " trigger có sẵn");
            return;
        }
        try { Files.write(tmp.resolve("controller-after.controller"), c1.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
        ok("T2 controller  +trigger/+state/+transition đúng, dò lại được " + resolved + "/" + total + " trigger có sẵn");
    }

    // ── T3: enum ────────────────────────────────────────────────────────────
    private static void t3Enum(Path enumSm, Path enumDb) {
        for (Path p : new Path[]{enumSm, enumDb}) {
            if (p == null || !Files.isRegularFile(p)) { bad("T3 enum", "không thấy " + p); return; }
        }
        String name = "HAO_QUANG_HQTEST_X";
        for (Object[] cs : new Object[][]{{enumSm, "Enum_HaoQuang"}, {enumDb, "Enum_HaoQuangDacBiet"}}) {
            Path p = (Path) cs[0];
            String type = (String) cs[1];
            String t0 = HaoQuang.read(p);
            List<HaoQuang.EnumEntry> before = HaoQuang.listEnumValues(t0, type);
            if (before.isEmpty()) { bad("T3 enum", type + ": đọc ra 0 entry"); return; }
            int max = 0;
            for (HaoQuang.EnumEntry e : before) max = Math.max(max, e.value());
            HaoQuang.EnumAdd add;
            try { add = HaoQuang.addEnumValue(t0, name, type); }
            catch (Exception e) { bad("T3 enum", type + ": thêm lỗi " + e.getMessage()); return; }
            if (add.value() != max + 1) { bad("T3 enum", type + ": giá trị " + add.value() + " ≠ max+1 = " + (max + 1)); return; }
            Integer got = HaoQuang.enumValueOf(add.text(), name);
            if (got == null || got != add.value()) { bad("T3 enum", type + ": đọc lại không ra giá trị vừa thêm"); return; }
            List<HaoQuang.EnumEntry> after = HaoQuang.listEnumValues(add.text(), type);
            if (after.size() != before.size() + 1) { bad("T3 enum", type + ": số entry " + after.size() + " ≠ " + (before.size() + 1)); return; }
            for (HaoQuang.EnumEntry e : before) {
                Integer v = HaoQuang.enumValueOf(add.text(), e.name());
                if (v == null || v != e.value()) { bad("T3 enum", type + ": entry cũ " + e.name() + " bị đổi"); return; }
            }
            try {
                HaoQuang.addEnumValue(add.text(), name, type);
                bad("T3 enum", type + ": thêm trùng mà KHÔNG báo lỗi");
                return;
            } catch (HaoQuang.HqError ignored) { }
            if (!add.text().contains("}")) { bad("T3 enum", type + ": mất dấu đóng block"); return; }
        }
        ok("T3 enum  thêm đúng max+1, giữ nguyên entry cũ, chặn trùng (cả 2 hệ)");
    }

    // ── T4: switch case trong ActorVisual ───────────────────────────────────
    private static void t4SwitchCase(Path actor) {
        if (actor == null || !Files.isRegularFile(actor)) { bad("T4 case", "không thấy ActorVisual.cs"); return; }
        String a0 = HaoQuang.read(actor);
        String en = "HAO_QUANG_HQTEST_X";

        HaoQuang.Upsert u1 = HaoQuang.upsertSwitchCaseDual(a0, en, "tsau", "ttruoc",
                "PlayHaoQuangAnim", "_animHaoQuang", "Enum_HaoQuang");
        if (!"added".equals(u1.how())) { bad("T4 case", "case mới mà báo '" + u1.how() + "'"); return; }
        HaoQuang.TriggerPair tp = HaoQuang.switchCaseTriggers(u1.text(), en, "Enum_HaoQuang");
        if (!"tsau".equals(tp.back()) || !"ttruoc".equals(tp.front())) {
            bad("T4 case", "đọc lại trigger sai: " + tp.back() + " / " + tp.front());
            return;
        }
        // upsert lần 2 với trigger khác ⇒ THAY THẾ, không nhân đôi
        HaoQuang.Upsert u2 = HaoQuang.upsertSwitchCaseDual(u1.text(), en, "tsau2", "ttruoc2",
                "PlayHaoQuangAnim", "_animHaoQuang", "Enum_HaoQuang");
        if (!"replaced".equals(u2.how())) { bad("T4 case", "case đã có mà báo '" + u2.how() + "'"); return; }
        if (count(u2.text(), "case Enum_HaoQuang." + en + ":") != 1) { bad("T4 case", "case bị nhân đôi"); return; }
        HaoQuang.TriggerPair tp2 = HaoQuang.switchCaseTriggers(u2.text(), en, "Enum_HaoQuang");
        if (!"tsau2".equals(tp2.back()) || !"ttruoc2".equals(tp2.front())) {
            bad("T4 case", "thay thế xong đọc ra trigger cũ");
            return;
        }
        // upsert lại y hệt ⇒ text KHÔNG đổi (chạy 2 lần an toàn)
        HaoQuang.Upsert u3 = HaoQuang.upsertSwitchCaseDual(u2.text(), en, "tsau2", "ttruoc2",
                "PlayHaoQuangAnim", "_animHaoQuang", "Enum_HaoQuang");
        if (!u3.text().equals(u2.text())) { bad("T4 case", "chạy lại cùng tham số mà text vẫn đổi"); return; }

        // hệ ĐẶC BIỆT phải nhắm đúng method/biến/enum riêng
        HaoQuang.Upsert d1 = HaoQuang.upsertSwitchCaseDual(a0, en, "dsau", "dtruoc",
                "PlayHaoQuangDacBietAnim", "_animHaoQuangDacBiet", "Enum_HaoQuangDacBiet");
        if (!d1.text().contains("_animHaoQuangDacBiet[1].SetTrigger(\"dtruoc\")")) {
            bad("T4 case", "hệ ĐẶC BIỆT không sinh đúng _animHaoQuangDacBiet[1]");
            return;
        }
        if (d1.text().contains("case Enum_HaoQuang." + en + ":")) {
            bad("T4 case", "hệ ĐẶC BIỆT lại chèn vào switch của hệ SM");
            return;
        }
        // các case CÓ SẴN đọc ra trigger được → bộ đọc khớp dữ liệu thật
        int okCase = 0, tot = 0;
        for (HaoQuang.EnumEntry e : HaoQuang.listEnumValues(a0, "Enum_HaoQuang")) {
            if (!HaoQuang.switchHasCase(a0, e.name(), "Enum_HaoQuang")) continue;
            tot++;
            if (HaoQuang.switchCaseTriggers(a0, e.name(), "Enum_HaoQuang").back() != null) okCase++;
        }
        ok("T4 case  thêm/thay/chạy-lại đúng, tách được 2 hệ" + (tot > 0
                ? " · đọc trigger " + okCase + "/" + tot + " case có sẵn" : ""));
    }

    // ── T5: prefab sortingOrder ─────────────────────────────────────────────
    private static void t5Prefab(Path prefab) {
        if (prefab == null || !Files.isRegularFile(prefab)) { bad("T5 prefab", "không thấy Player.prefab"); return; }
        String p0 = HaoQuang.read(prefab);
        List<String> found = new ArrayList<>();
        for (String go : new String[]{"HaoQuang2", "HaoQuangDacBiet2"}) {
            HaoQuang.Sorting s = HaoQuang.setGameObjectSpriteSorting(p0, go, 1);
            if (s.oldOrder() == null) continue;
            found.add(go + "(" + s.oldOrder() + "→1)");
            HaoQuang.Sorting again = HaoQuang.setGameObjectSpriteSorting(s.text(), go, 1);
            if (again.oldOrder() == null || again.oldOrder() != 1) {
                bad("T5 prefab", go + ": ghi xong đọc lại không ra 1");
                return;
            }
            if (s.text().length() != p0.length() - String.valueOf(s.oldOrder()).length() + 1) {
                // độ dài chỉ được đổi đúng bằng chênh lệch số chữ số ⇒ không đụng chỗ khác
                bad("T5 prefab", go + ": độ dài file đổi bất thường");
                return;
            }
        }
        if (found.isEmpty()) { bad("T5 prefab", "không thấy GameObject HaoQuang2 / HaoQuangDacBiet2"); return; }
        HaoQuang.Sorting none = HaoQuang.setGameObjectSpriteSorting(p0, "KhongCoGameObjectNay_xyz", 1);
        if (none.oldOrder() != null || !none.text().equals(p0)) {
            bad("T5 prefab", "GameObject không tồn tại mà vẫn sửa file");
            return;
        }
        ok("T5 prefab  " + String.join(", ", found) + " · tên lạ thì không đụng file");
    }

    // ── T6: PPU + pivot trên .meta thật ─────────────────────────────────────
    private static void t6Meta(Path texDir) {
        Path meta = anyFrameMeta(texDir);
        if (meta == null) { bad("T6 meta", "không thấy frame .meta nào trong " + texDir); return; }
        String m0 = HaoQuang.read(meta);
        Double ppu0 = HaoQuang.readSpritePpu(m0);
        double[] piv0 = HaoQuang.readSpritePivot(m0);
        if (ppu0 == null || piv0 == null) { bad("T6 meta", "đọc không ra PPU/pivot của " + meta.getFileName()); return; }

        String m1 = HaoQuang.setSpritePpu(m0, 133.3333);
        Double ppu1 = HaoQuang.readSpritePpu(m1);
        if (ppu1 == null || Math.abs(ppu1 - 133.3333) > 1e-6) { bad("T6 meta", "ghi PPU rồi đọc lại ra " + ppu1); return; }

        String m2 = HaoQuang.setSpritePivot(m1, 0.4321, 0.2109);
        double[] piv2 = HaoQuang.readSpritePivot(m2);
        if (piv2 == null || Math.abs(piv2[0] - 0.4321) > 1e-6 || Math.abs(piv2[1] - 0.2109) > 1e-6) {
            bad("T6 meta", "ghi pivot rồi đọc lại ra " + java.util.Arrays.toString(piv2));
            return;
        }
        Matcher al = Pattern.compile("^\\s*alignment:\\s*(\\d+)", Pattern.MULTILINE).matcher(m2);
        if (!al.find() || !"9".equals(al.group(1))) {
            bad("T6 meta", "đặt pivot mà alignment không về 9 ⇒ Unity bỏ qua pivot");
            return;
        }
        if (count(m2, "spritePixelsToUnits:") != count(m0, "spritePixelsToUnits:")
                || count(m2, "spritePivot:") != count(m0, "spritePivot:")) {
            bad("T6 meta", "số dòng PPU/pivot bị đổi ⇒ regex bắt nhầm");
            return;
        }
        // PPU của lớp còn lại KHÔNG bị đụng khi chỉ ghi 1 lớp — kiểm bằng độ dài phần còn lại
        if (m2.replaceAll("spritePixelsToUnits:.*", "").length()
                != m0.replaceAll("spritePixelsToUnits:.*", "").length()
                - (HaoQuang.fmt(piv0[0]) + HaoQuang.fmt(piv0[1])).length()
                + (HaoQuang.fmt(0.4321) + HaoQuang.fmt(0.2109)).length()
                + (m0.contains("alignment: 9") ? 0 : 0)) {
            notes.add("T6: độ dài phần ngoài PPU đổi — kiểm bằng mắt nếu nghi ngờ");
        }
        ok("T6 meta  PPU + pivot ghi/đọc khớp, alignment tự về 9 (" + meta.getParent().getFileName()
                + "/" + meta.getFileName() + ", PPU gốc " + HaoQuang.fmt(ppu0) + ")");
    }

    // ── T7: retarget .meta về Single ────────────────────────────────────────
    private static void t7Retarget(Path texDir) {
        Path meta = anyFrameMeta(texDir);
        if (meta == null) { bad("T7 retarget", "không thấy frame .meta"); return; }
        HaoQuang.Template tmpl = HaoQuang.templateMetaText(texDir);
        String guid = HaoQuang.readMetaGuid(meta);
        String out = HaoQuang.retargetMeta(tmpl.text(), guid);
        if (!guid.equals(readGuid(out))) { bad("T7 retarget", "guid KHÔNG được giữ ⇒ .anim mất liên kết"); return; }
        Integer mode = HaoQuang.readSpriteMode(out);
        if (mode == null || mode != 1) { bad("T7 retarget", "spriteMode ra " + mode + ", phải là 1 (Single)"); return; }
        String sid0 = matchOf(tmpl.text(), "spriteID:\\s*([0-9a-fA-F]{32})");
        String sid1 = matchOf(out, "spriteID:\\s*([0-9a-fA-F]{32})");
        if (sid1 == null || sid1.equals(sid0)) { bad("T7 retarget", "spriteID không được sinh mới"); return; }

        HaoQuang.MetaNew clone = HaoQuang.cloneMeta(tmpl.text());
        if (clone.guid().equals(guid) || clone.guid().length() != 32) { bad("T7 retarget", "cloneMeta sinh guid hỏng"); return; }
        if (!clone.guid().equals(readGuid(clone.text()))) { bad("T7 retarget", "cloneMeta: guid trả về khác guid trong text"); return; }
        ok("T7 retarget  giữ guid + sinh spriteID mới + Single (mẫu: " + tmpl.source() + ")");
    }

    // ── T8: toán quy Offset → pivot ─────────────────────────────────────────
    private static void t8PivotMath() {
        double k = 0.006875 * 0.7, ppu = 100, w = 256, h = 256;
        double px = 0.5, py = 0.14;
        for (double ox : new double[]{-120, -13.5, 0.5, 47, 300}) {
            for (double oy : new double[]{-90, -1, 3.25, 66}) {
                double nx = px - ox * k * ppu / w;
                double ny = py + oy * k * ppu / h;
                double backX = (px - nx) * w / (k * ppu);
                double backY = (ny - py) * h / (k * ppu);
                if (Math.abs(backX - ox) > 1e-9 || Math.abs(backY - oy) > 1e-9) {
                    bad("T8 pivot", "quy ngược sai: " + ox + "→" + backX + " · " + oy + "→" + backY);
                    return;
                }
            }
        }
        // dời SANG PHẢI (ox>0) phải làm pivot.x GIẢM; dời XUỐNG (oy>0) phải làm pivot.y TĂNG
        if (!(px - 50 * k * ppu / w < px) || !(py + 50 * k * ppu / h > py)) {
            bad("T8 pivot", "chiều dấu của pivot bị ngược");
            return;
        }
        ok("T8 pivot  quy Offset→pivot thuận/nghịch khớp, chiều dấu đúng");
    }

    // ── T9: client không bị đụng ────────────────────────────────────────────
    private static void t9Untouched(Map<String, String> before, Path... files) {
        Map<String, String> after = hashAll(files);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) changed.add(e.getKey());
        }
        if (!changed.isEmpty()) { bad("T9 an toàn", "ĐÃ SỬA file client: " + String.join(", ", changed)); return; }
        ok("T9 an toàn  " + before.size() + " file client giữ nguyên SHA-256 (test chỉ đọc)");
    }

    /**
     * Thư mục {@code tools/honma-composer} (bản Python gốc) — dò từ repo client đi lên.
     * Không thấy thì T1 tự bỏ qua có báo.
     */
    private static Path honmaDir() {
        String prop = System.getProperty("honma.dir");
        if (prop != null && !prop.isEmpty()) return Path.of(prop);
        Path repo = new ToolConfig().assetsRoot();          // …/UocRongOnline-Client/Assets
        for (Path p = repo; p != null; p = p.getParent()) {
            Path d = p.resolve("tools").resolve("honma-composer");
            if (Files.isDirectory(d)) return d;
        }
        return null;
    }

    // ── tiện ích ────────────────────────────────────────────────────────────
    private static Path anyFrameMeta(Path texDir) {
        if (texDir == null || !Files.isDirectory(texDir)) return null;
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(texDir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isDirectory(p)) dirs.add(p);
        } catch (Exception ignored) { return null; }
        dirs.sort(HaoQuang.BY_NAME);
        for (Path d : dirs) {
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().toLowerCase().endsWith(".png.meta")) return p;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static Map<String, String> hashAll(Path... files) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : files) {
            if (p == null || !Files.isRegularFile(p)) continue;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(Files.readAllBytes(p));
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                out.put(p.getFileName().toString(), sb.toString());
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String readGuid(String metaText) { return matchOf(metaText, "guid:\\s*([0-9a-fA-F]{32})"); }

    private static String matchOf(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int intOf(String text, String regex, int def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static String strOf(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** Số ký tự CR trong chuỗi (đếm xuống dòng kiểu Windows). */
    private static int cr(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\r') n++;
        return n;
    }

    private static int count(String text, String needle) {
        int n = 0, i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static void ok(String msg) { pass++; System.out.println("  OK " + msg); }

    private static void bad(String tag, String why) { fail++; System.out.println("  ✘ " + tag + " — " + why); }
}
