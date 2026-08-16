package com.apex.maptool.unity;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sinh &amp; sửa file Hào Quang trong client Unity — bản Java của
 * {@code tools/honma-composer/hao_quang_export.py}.
 *
 * <p>Hào quang trong game KHÔNG phải Spine: nó là <b>Unity AnimationClip</b> hoán đổi sprite theo
 * thời gian, phát qua {@code HaoQuang.controller} (1 trigger → 1 state → 1 clip), kích từ
 * {@code ActorVisual.PlayHaoQuangAnim} theo {@code Enum_HaoQuang}.
 *
 * <h2>Nguyên tắc: THUẦN VĂN BẢN, KHÔNG TỰ GHI ĐĨA</h2>
 * Mọi hàm sửa file đều nhận {@code text} và trả về {@code text mới}. Nhờ vậy UI dựng được TOÀN BỘ
 * thay đổi trong RAM trước; hỏng ở bước nào thì <b>chưa file nào bị đụng</b>. Đây là điều kiện sống
 * còn vì các file đích là mã nguồn + asset THẬT của client.
 *
 * <h2>Hai hệ hào quang</h2>
 * <ul>
 *   <li><b>SM</b> — {@code Enum_HaoQuang} · {@code PlayHaoQuangAnim} · {@code _animHaoQuang} ·
 *       GameObject lớp trước {@code HaoQuang2}</li>
 *   <li><b>ĐẶC BIỆT</b> (cosmetic, độc lập) — {@code Enum_HaoQuangDacBiet} ·
 *       {@code PlayHaoQuangDacBietAnim} · {@code _animHaoQuangDacBiet} · {@code HaoQuangDacBiet2}</li>
 * </ul>
 *
 * <h2>Căn chỉnh = ghi vào .meta của FRAME, không phải vào clip</h2>
 * <ul>
 *   <li><b>Cỡ</b> ← {@code spritePixelsToUnits} (PPU). PPU <b>nhỏ hơn = sprite TO hơn</b>.</li>
 *   <li><b>Vị trí</b> ← {@code spritePivot} (0..1, gốc dưới-trái) + bắt buộc {@code alignment: 9}
 *       (custom) thì Unity mới dùng pivot đó. Điểm pivot được đặt tại gốc spine (chân nhân vật).</li>
 * </ul>
 */
public final class HaoQuang {

    /** Sprite sub-asset fileID cho PNG import ở chế độ Single. */
    public static final int SPRITE_FILEID = 21300000;
    /** mainObjectFileID của AnimationClip. */
    public static final int CLIP_MAIN_FILEID = 7400000;
    /** fileID của AnimatorController trong HaoQuang.controller. */
    public static final int CONTROLLER_FILEID = 9100000;
    /** m_Type cho parameter kiểu Trigger. */
    public static final int PARAM_TYPE_TRIGGER = 9;

    private HaoQuang() { }

    /** Lỗi nghiệp vụ (thiếu file, trùng trigger, enum đã có…) — luôn có thông điệp đọc được. */
    public static final class HqError extends RuntimeException {
        public HqError(String msg) { super(msg); }
    }

    // ════════════════════ tiện ích ════════════════════

    /** GUID 32 ký tự hex đúng định dạng .meta của Unity. */
    public static String newGuid() {
        UUID u = UUID.randomUUID();
        return String.format("%016x%016x", u.getMostSignificantBits(), u.getLeastSignificantBits());
    }

    /**
     * Format số kiểu Unity: {@code 0 → "0"}, {@code 0.2 → "0.2"}, {@code 1/15 → "0.066666667"}.
     * Bám {@code f"{v:.8g}"} của Python (8 chữ số có nghĩa, bỏ số 0 thừa) — nếu để
     * {@code String.format("%.8g")} thì Java giữ nguyên "0.20000000", khác file Unity sinh ra.
     */
    public static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-12) return String.valueOf((long) Math.rint(v));
        BigDecimal bd = new BigDecimal(v).round(new MathContext(8)).stripTrailingZeros();
        // Luật %g của C/Python: số quá nhỏ (mũ < -4) hoặc quá lớn (mũ ≥ số chữ số có nghĩa) thì
        // viết dạng mũ "1e-07". Bỏ nhánh này là 1e-7 ra "0.0000001" — khác bản Python.
        int exp10 = bd.precision() - bd.scale() - 1;
        if (exp10 < -4 || exp10 >= 8) {
            String mant = bd.movePointLeft(exp10).stripTrailingZeros().toPlainString();
            return mant + "e" + (exp10 < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp10));
        }
        return bd.toPlainString();
    }

    /** So sánh tên "tự nhiên": {@code Lua_2 < Lua_10} (tách cụm số ra so bằng số). */
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    public static final Comparator<Path> BY_NAME = (p, q) ->
            naturalCompare(p.getFileName().toString(), q.getFileName().toString());

    /** public vì {@code ui.HaoQuangPanel} (khác package) đọc file qua đây. */
    public static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HqError("Không đọc được " + p + ": " + e.getMessage());
        }
    }

    public static void write(Path p, String text) {
        try {
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HqError("Không ghi được " + p + ": " + e.getMessage());
        }
    }

    private static final Pattern P_GUID =
            Pattern.compile("^guid:\\s*([0-9a-fA-F]{32})\\s*$", Pattern.MULTILINE);

    /** Đọc dòng {@code guid:} trong 1 file .meta. */
    public static String readMetaGuid(Path meta) {
        Matcher m = P_GUID.matcher(read(meta));
        if (!m.find()) throw new HqError("Không thấy guid trong " + meta.getFileName());
        return m.group(1);
    }

    /** Một frame PNG của hào quang: tên file + guid trong .meta. */
    public record Frame(String name, String guid) { }

    /** Mọi {@code *.png} trong folder, sắp tự nhiên. */
    public static List<Path> findFramePngs(Path folder) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".png")) out.add(p);
            }
        } catch (IOException e) {
            throw new HqError("Không đọc được thư mục " + folder + ": " + e.getMessage());
        }
        out.sort(BY_NAME);
        return out;
    }

    /**
     * (tên file, guid) cho từng frame. Ném lỗi nếu frame nào thiếu {@code .meta} — nghĩa là
     * chưa import vào Unity, mà clip trỏ guid rỗng thì game hiện ô trắng.
     */
    public static List<Frame> collectFrameGuids(Path folder) {
        List<Path> pngs = findFramePngs(folder);
        if (pngs.isEmpty()) throw new HqError("Không có frame .png nào trong " + folder);
        List<Frame> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Path p : pngs) {
            Path meta = p.resolveSibling(p.getFileName() + ".meta");
            if (!Files.isRegularFile(meta)) { missing.add(p.getFileName().toString()); continue; }
            out.add(new Frame(p.getFileName().toString(), readMetaGuid(meta)));
        }
        if (!missing.isEmpty()) {
            throw new HqError("Các frame sau chưa import vào Unity (thiếu .meta): " + String.join(", ", missing));
        }
        return out;
    }

    // ════════════════════ 1) .anim + .meta ════════════════════

    /**
     * Nội dung AnimationClip (.anim) hoán đổi sprite — cùng khuôn với {@code aniSaiya.anim} của game.
     *
     * @param interval khoảng cách 2 frame (mặc định 1/15 s)
     */
    public static String buildAnimClipYaml(String clipName, List<String> frameGuids,
                                           double interval, int sampleRate, boolean loop) {
        if (frameGuids == null || frameGuids.isEmpty()) throw new HqError("Clip phải có ít nhất 1 frame.");
        StringBuilder curve = new StringBuilder();
        StringBuilder mapping = new StringBuilder();
        for (int i = 0; i < frameGuids.size(); i++) {
            String ref = "{fileID: " + SPRITE_FILEID + ", guid: " + frameGuids.get(i) + ", type: 3}";
            curve.append("    - time: ").append(fmt(i * interval)).append('\n');
            curve.append("      value: ").append(ref).append('\n');
            mapping.append("    - ").append(ref).append('\n');
        }
        // bỏ '\n' cuối để khớp nguyên văn khuôn Python (join thay vì append)
        trimLast(curve);
        trimLast(mapping);
        double stopTime = (frameGuids.size() - 1) * interval + 1.0 / sampleRate;

        return "%YAML 1.1\n"
                + "%TAG !u! tag:unity3d.com,2011:\n"
                + "--- !u!74 &" + CLIP_MAIN_FILEID + "\n"
                + "AnimationClip:\n"
                + "  m_ObjectHideFlags: 0\n"
                + "  m_CorrespondingSourceObject: {fileID: 0}\n"
                + "  m_PrefabInstance: {fileID: 0}\n"
                + "  m_PrefabAsset: {fileID: 0}\n"
                + "  m_Name: " + clipName + "\n"
                + "  serializedVersion: 7\n"
                + "  m_Legacy: 0\n"
                + "  m_Compressed: 0\n"
                + "  m_UseHighQualityCurve: 1\n"
                + "  m_RotationCurves: []\n"
                + "  m_CompressedRotationCurves: []\n"
                + "  m_EulerCurves: []\n"
                + "  m_PositionCurves: []\n"
                + "  m_ScaleCurves: []\n"
                + "  m_FloatCurves: []\n"
                + "  m_PPtrCurves:\n"
                + "  - serializedVersion: 2\n"
                + "    curve:\n"
                + curve + "\n"
                + "    attribute: m_Sprite\n"
                + "    path:\n"
                + "    classID: 212\n"
                + "    script: {fileID: 0}\n"
                + "    flags: 2\n"
                + "  m_SampleRate: " + sampleRate + "\n"
                + "  m_WrapMode: 0\n"
                + "  m_Bounds:\n"
                + "    m_Center: {x: 0, y: 0, z: 0}\n"
                + "    m_Extent: {x: 0, y: 0, z: 0}\n"
                + "  m_ClipBindingConstant:\n"
                + "    genericBindings:\n"
                + "    - serializedVersion: 2\n"
                + "      path: 0\n"
                + "      attribute: 0\n"
                + "      script: {fileID: 0}\n"
                + "      typeID: 212\n"
                + "      customType: 23\n"
                + "      isPPtrCurve: 1\n"
                + "      isIntCurve: 0\n"
                + "      isSerializeReferenceCurve: 0\n"
                + "    pptrCurveMapping:\n"
                + mapping + "\n"
                + "  m_AnimationClipSettings:\n"
                + "    serializedVersion: 2\n"
                + "    m_AdditiveReferencePoseClip: {fileID: 0}\n"
                + "    m_AdditiveReferencePoseTime: 0\n"
                + "    m_StartTime: 0\n"
                + "    m_StopTime: " + fmt(stopTime) + "\n"
                + "    m_OrientationOffsetY: 0\n"
                + "    m_Level: 0\n"
                + "    m_CycleOffset: 0\n"
                + "    m_HasAdditiveReferencePose: 0\n"
                + "    m_LoopTime: " + (loop ? 1 : 0) + "\n"
                + "    m_LoopBlend: 0\n"
                + "    m_LoopBlendOrientation: 0\n"
                + "    m_LoopBlendPositionY: 0\n"
                + "    m_LoopBlendPositionXZ: 0\n"
                + "    m_KeepOriginalOrientation: 0\n"
                + "    m_KeepOriginalPositionY: 1\n"
                + "    m_KeepOriginalPositionXZ: 0\n"
                + "    m_HeightFromFeet: 0\n"
                + "    m_Mirror: 0\n"
                + "  m_EditorCurves: []\n"
                + "  m_EulerEditorCurves: []\n"
                + "  m_HasGenericRootTransform: 0\n"
                + "  m_HasMotionFloatCurves: 0\n"
                + "  m_Events: []\n";
    }

    private static void trimLast(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
    }

    public static String buildAnimMetaYaml(String guid) {
        return "fileFormatVersion: 2\n"
                + "guid: " + guid + "\n"
                + "NativeFormatImporter:\n"
                + "  externalObjects: {}\n"
                + "  mainObjectFileID: " + CLIP_MAIN_FILEID + "\n"
                + "  userData:\n"
                + "  assetBundleName:\n"
                + "  assetBundleVariant:\n";
    }

    // ════════════════════ 2) controller ════════════════════

    /** fileID 63-bit dương chưa xuất hiện trong text (để gắn AnimatorState / Transition). */
    private static long genUniqueFileId(String text) {
        for (int i = 0; i < 10000; i++) {
            long fid = UUID.randomUUID().getMostSignificantBits() & ((1L << 63) - 1);
            if (fid == 0) continue;
            if (!text.contains("&" + fid) && !text.contains("fileID: " + fid + "}")) return fid;
        }
        throw new HqError("Không sinh được fileID duy nhất.");
    }

    /** Trigger đã có trong controller chưa (dò theo điều kiện transition). */
    public static boolean controllerHasTrigger(String controllerText, String trigger) {
        return Pattern.compile("m_ConditionEvent:\\s*" + Pattern.quote(trigger) + "\\b")
                .matcher(controllerText).find();
    }

    /**
     * Thêm vào {@code HaoQuang.controller}: 1 parameter trigger, 1 AnimatorState trỏ clip, và
     * 1 AnyState transition điều kiện trigger → state đó.
     */
    public static String addParamStateTransition(String text, String trigger, String stateName, String clipGuid) {
        boolean hasParam = Pattern.compile("^\\s*-?\\s*m_Name:\\s*" + Pattern.quote(trigger) + "\\s*$",
                Pattern.MULTILINE).matcher(text).find();
        if (hasParam && controllerHasTrigger(text, trigger)) {
            throw new HqError("Trigger '" + trigger + "' đã tồn tại trong controller.");
        }
        long stateId = genUniqueFileId(text);
        long transId = genUniqueFileId(text + stateId);

        String paramBlock = "  - m_Name: " + trigger + "\n"
                + "    m_Type: " + PARAM_TYPE_TRIGGER + "\n"
                + "    m_DefaultFloat: 0\n"
                + "    m_DefaultInt: 0\n"
                + "    m_DefaultBool: 0\n"
                + "    m_Controller: {fileID: " + CONTROLLER_FILEID + "}\n";
        if (!text.contains("\n  m_AnimatorLayers:")) throw new HqError("Controller thiếu m_AnimatorLayers.");
        text = replaceFirstLiteral(text, "\n  m_AnimatorLayers:", "\n" + paramBlock + "  m_AnimatorLayers:");

        String childBlock = "  - serializedVersion: 1\n"
                + "    m_State: {fileID: " + stateId + "}\n"
                + "    m_Position: {x: 530, y: 200, z: 0}\n";
        if (!text.contains("\n  m_ChildStateMachines:")) throw new HqError("Controller thiếu m_ChildStateMachines.");
        text = replaceFirstLiteral(text, "\n  m_ChildStateMachines:", "\n" + childBlock + "  m_ChildStateMachines:");

        String anyBlock = "  - {fileID: " + transId + "}\n";
        if (!text.contains("\n  m_EntryTransitions:")) throw new HqError("Controller thiếu m_EntryTransitions.");
        text = replaceFirstLiteral(text, "\n  m_EntryTransitions:", "\n" + anyBlock + "  m_EntryTransitions:");

        if (!text.endsWith("\n")) text += "\n";
        text += "--- !u!1102 &" + stateId + "\n"
                + "AnimatorState:\n"
                + "  serializedVersion: 6\n"
                + "  m_ObjectHideFlags: 1\n"
                + "  m_CorrespondingSourceObject: {fileID: 0}\n"
                + "  m_PrefabInstance: {fileID: 0}\n"
                + "  m_PrefabAsset: {fileID: 0}\n"
                + "  m_Name: " + stateName + "\n"
                + "  m_Speed: 1\n"
                + "  m_CycleOffset: 0\n"
                + "  m_Transitions: []\n"
                + "  m_StateMachineBehaviours: []\n"
                + "  m_Position: {x: 50, y: 50, z: 0}\n"
                + "  m_IKOnFeet: 0\n"
                + "  m_WriteDefaultValues: 1\n"
                + "  m_Mirror: 0\n"
                + "  m_SpeedParameterActive: 0\n"
                + "  m_MirrorParameterActive: 0\n"
                + "  m_CycleOffsetParameterActive: 0\n"
                + "  m_TimeParameterActive: 0\n"
                + "  m_Motion: {fileID: " + CLIP_MAIN_FILEID + ", guid: " + clipGuid + ", type: 2}\n"
                + "  m_Tag: \n"
                + "  m_SpeedParameter: \n"
                + "  m_MirrorParameter: \n"
                + "  m_CycleOffsetParameter: \n"
                + "  m_TimeParameter: \n"
                + "--- !u!1101 &" + transId + "\n"
                + "AnimatorStateTransition:\n"
                + "  m_ObjectHideFlags: 1\n"
                + "  m_CorrespondingSourceObject: {fileID: 0}\n"
                + "  m_PrefabInstance: {fileID: 0}\n"
                + "  m_PrefabAsset: {fileID: 0}\n"
                + "  m_Name: \n"
                + "  m_Conditions:\n"
                + "  - m_ConditionMode: 1\n"
                + "    m_ConditionEvent: " + trigger + "\n"
                + "    m_EventTreshold: 0\n"
                + "  m_DstStateMachine: {fileID: 0}\n"
                + "  m_DstState: {fileID: " + stateId + "}\n"
                + "  m_Solo: 0\n"
                + "  m_Mute: 0\n"
                + "  m_IsExit: 0\n"
                + "  serializedVersion: 3\n"
                + "  m_TransitionDuration: 0.25\n"
                + "  m_TransitionOffset: 0\n"
                + "  m_ExitTime: 0.75\n"
                + "  m_HasExitTime: 0\n"
                + "  m_HasFixedDuration: 1\n"
                + "  m_InterruptionSource: 0\n"
                + "  m_OrderedInterruption: 1\n"
                + "  m_CanTransitionToSelf: 1\n";
        return text;
    }

    /** Với 1 trigger có sẵn, trả về guid clip mà state đích đang dùng (null nếu không lần ra). */
    public static String controllerTriggerClipGuid(String controllerText, String trigger) {
        Map<String, String> states = new LinkedHashMap<>();
        String dst = null;
        Pattern pState = Pattern.compile("^!u!1102 &(-?\\d+)");
        Pattern pTrans = Pattern.compile("^!u!1101 &(-?\\d+)");
        Pattern pMotion = Pattern.compile("m_Motion:\\s*\\{fileID:\\s*\\d+,\\s*guid:\\s*([0-9a-fA-F]{32})");
        Pattern pDst = Pattern.compile("m_DstState:\\s*\\{fileID:\\s*(-?\\d+)\\}");
        Pattern pCond = Pattern.compile("m_ConditionEvent:\\s*" + Pattern.quote(trigger) + "\\b");
        for (String block : controllerText.split("(?m)^--- ")) {
            Matcher ms = pState.matcher(block);
            if (ms.lookingAt() && block.contains("AnimatorState:")) {
                Matcher mg = pMotion.matcher(block);
                states.put(ms.group(1), mg.find() ? mg.group(1) : null);
            }
            Matcher mt = pTrans.matcher(block);
            if (mt.lookingAt() && block.contains("AnimatorStateTransition:") && pCond.matcher(block).find()) {
                Matcher md = pDst.matcher(block);
                if (md.find()) dst = md.group(1);
            }
        }
        return dst == null ? null : states.get(dst);
    }

    /** File {@code .anim} trong folder có {@code .meta} chứa guid này. */
    public static Path findAnimByGuid(Path folder, String guid) {
        if (folder == null || !Files.isDirectory(folder)) return null;
        List<Path> anims = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().toLowerCase().endsWith(".anim")) anims.add(p);
            }
        } catch (IOException ignored) { return null; }
        anims.sort(BY_NAME);
        for (Path p : anims) {
            Path meta = p.resolveSibling(p.getFileName() + ".meta");
            if (Files.isRegularFile(meta) && read(meta).toLowerCase().contains(guid.toLowerCase())) return p;
        }
        return null;
    }

    // ════════════════════ 3) enum ════════════════════

    /** Giá trị enum nếu đã có, null nếu chưa. */
    public static Integer enumValueOf(String text, String name) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)", Pattern.MULTILINE).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Một entry enum. */
    public record EnumEntry(String name, int value) { }

    /** Liệt kê (tên, giá trị) trong đúng block {@code enum <enumName> { … }}. */
    public static List<EnumEntry> listEnumValues(String text, String enumName) {
        Matcher mb = Pattern.compile("enum\\s+" + Pattern.quote(enumName) + "\\b.*?\\{(.*?)\\}", Pattern.DOTALL)
                .matcher(text);
        String body = mb.find() ? mb.group(1) : text;
        List<EnumEntry> out = new ArrayList<>();
        Matcher m = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*=\\s*(\\d+)", Pattern.MULTILINE).matcher(body);
        while (m.find()) out.add(new EnumEntry(m.group(1), Integer.parseInt(m.group(2))));
        return out;
    }

    /** Kết quả thêm enum: text mới + giá trị được cấp. */
    public record EnumAdd(String text, int value) { }

    /** Thêm {@code name = <max+1>,} vào enum (trước dấu {@code }} cuối block). */
    public static EnumAdd addEnumValue(String text, String name, String enumName) {
        if (Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s*=", Pattern.MULTILINE).matcher(text).find()) {
            throw new HqError("Enum '" + name + "' đã tồn tại.");
        }
        int next = 0;
        Matcher mv = Pattern.compile("=\\s*(\\d+)\\s*,").matcher(text);
        boolean any = false;
        while (mv.find()) { next = Math.max(next, Integer.parseInt(mv.group(1))); any = true; }
        next = any ? next + 1 : 0;

        Matcher m = Pattern.compile("(enum\\s+" + Pattern.quote(enumName) + "\\b.*?)(\\n[ \\t]*\\})", Pattern.DOTALL)
                .matcher(text);
        if (!m.find()) throw new HqError("Không thấy enum " + enumName + ".");
        String body = m.group(1), closing = m.group(2);
        String body2 = body.stripTrailing().endsWith(",") ? body
                : body.replaceFirst("(=\\s*\\d+)\\s*(\\n[ \\t]*)$", "$1,$2");
        String newBlock = body2.replaceAll("\\n+$", "") + "\n    " + name + " = " + next + "," + closing;
        return new EnumAdd(text.substring(0, m.start()) + newBlock + text.substring(m.end()), next);
    }

    // ════════════════════ 4) switch case trong ActorVisual ════════════════════

    public static boolean switchHasCase(String text, String enumName, String enumType) {
        return Pattern.compile("case\\s+" + Pattern.quote(enumType) + "\\." + Pattern.quote(enumName) + "\\s*:")
                .matcher(text).find();
    }

    /** Cặp trigger (lớp SAU, lớp TRƯỚC) đọc từ case — lớp trước null nếu case chỉ 1 lớp. */
    public record TriggerPair(String back, String front) { }

    /**
     * Đọc trigger của 1 case. Case 2 lớp có dạng {@code <anim>[0].SetTrigger("sau")} +
     * {@code <anim>[1].SetTrigger("trước")}; trigger trùng tên folder frame nên từ đây lần ra
     * được cả 2 folder ảnh lúc import lại.
     */
    public static TriggerPair switchCaseTriggers(String text, String enumName, String enumType) {
        Matcher m = Pattern.compile("case\\s+" + Pattern.quote(enumType) + "\\." + Pattern.quote(enumName)
                + "\\s*:(.*?)break\\s*;", Pattern.DOTALL).matcher(text);
        if (!m.find()) return new TriggerPair(null, null);
        String body = m.group(1);
        String back = null, front = null;
        Matcher mm = Pattern.compile("\\[(\\d+)\\]\\s*\\.\\s*SetTrigger\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(body);
        while (mm.find()) {
            int idx = Integer.parseInt(mm.group(1));
            if (idx == 0) back = mm.group(2);
            else if (idx == 1) front = mm.group(2);
        }
        if (back == null) {   // bản 1 lớp kiểu cũ không có chỉ số
            Matcher m1 = Pattern.compile("SetTrigger\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(body);
            if (m1.find()) back = m1.group(1);
        }
        return new TriggerPair(back, front);
    }

    /** Thêm case 1 lớp (chỉ slot 0), chèn ngay trước {@code default:}. */
    public static String addSwitchCase(String text, String enumName, String trigger) {
        if (switchHasCase(text, enumName, "Enum_HaoQuang")) {
            throw new HqError("Switch đã có case Enum_HaoQuang." + enumName + ".");
        }
        Ins ins = findDefaultInsert(text, "PlayHaoQuangAnim");
        String block = ins.indent + "case Enum_HaoQuang." + enumName + ":\n"
                + ins.indent + "    _animHaoQuang[0].gameObject.SetActive(true);\n"
                + ins.indent + "    _animHaoQuang[0].SetTrigger(\"" + trigger + "\");\n"
                + ins.indent + "    break;\n\n";
        return text.substring(0, ins.at) + block + text.substring(ins.at);
    }

    private record Ins(int at, String indent) { }

    private static Ins findDefaultInsert(String text, String methodName) {
        Matcher mm = Pattern.compile("IEnumerator\\s+" + Pattern.quote(methodName) + "\\b").matcher(text);
        if (!mm.find()) throw new HqError("Không thấy " + methodName + " trong ActorVisual.");
        Matcher dm = Pattern.compile("\\n([ \\t]*)default:\\s*\\n").matcher(text.substring(mm.end()));
        if (!dm.find()) throw new HqError("Không thấy 'default:' trong switch " + methodName + ".");
        return new Ins(mm.end() + dm.start() + 1, dm.group(1));
    }

    /** Thêm case bật CẢ HAI slot (0 = sau, 1 = trước). */
    public static String addSwitchCaseDual(String text, String enumName, String triggerBack, String triggerFront,
                                           String methodName, String animatorVar, String enumType) {
        if (switchHasCase(text, enumName, enumType)) {
            throw new HqError("Switch đã có case " + enumType + "." + enumName + ".");
        }
        Ins ins = findDefaultInsert(text, methodName);
        return text.substring(0, ins.at) + dualBlock(ins.indent, enumName, triggerBack, triggerFront,
                animatorVar, enumType) + "\n" + text.substring(ins.at);
    }

    private static String dualBlock(String indent, String enumName, String tBack, String tFront,
                                    String animatorVar, String enumType) {
        return indent + "case " + enumType + "." + enumName + ":\n"
                + indent + "    " + animatorVar + "[0].gameObject.SetActive(true);\n"
                + indent + "    " + animatorVar + "[1].gameObject.SetActive(true);\n"
                + indent + "    " + animatorVar + "[0].SetTrigger(\"" + tBack + "\");\n"
                + indent + "    " + animatorVar + "[1].SetTrigger(\"" + tFront + "\");\n"
                + indent + "    break;\n";
    }

    /** Kết quả upsert: text mới + đã "added" hay "replaced". */
    public record Upsert(String text, String how) { }

    /**
     * Bảo đảm case cho {@code enumName} bật CẢ HAI slot: chưa có thì thêm, có rồi (kể cả bản 1 lớp
     * cũ) thì THAY THẾ.
     */
    public static Upsert upsertSwitchCaseDual(String text, String enumName, String tBack, String tFront,
                                              String methodName, String animatorVar, String enumType) {
        Matcher mm = Pattern.compile("IEnumerator\\s+" + Pattern.quote(methodName) + "\\b").matcher(text);
        if (!mm.find()) throw new HqError("Không thấy " + methodName + " trong ActorVisual.");
        if (!switchHasCase(text, enumName, enumType)) {
            return new Upsert(addSwitchCaseDual(text, enumName, tBack, tFront, methodName, animatorVar, enumType),
                    "added");
        }
        Matcher m = Pattern.compile("([ \\t]*)case\\s+" + Pattern.quote(enumType) + "\\." + Pattern.quote(enumName)
                + "\\s*:.*?break;[ \\t]*\\r?\\n", Pattern.DOTALL).matcher(text);
        if (!m.find(mm.end())) {
            return new Upsert(addSwitchCaseDual(text, enumName, tBack, tFront, methodName, animatorVar, enumType),
                    "added");
        }
        String block = dualBlock(m.group(1), enumName, tBack, tFront, animatorVar, enumType);
        return new Upsert(text.substring(0, m.start()) + block + text.substring(m.end()), "replaced");
    }

    // ════════════════════ prefab: sorting order ════════════════════

    /** Kết quả đặt sortingOrder: text mới + giá trị cũ (null = không thấy GameObject / SpriteRenderer). */
    public record Sorting(String text, Integer oldOrder) { }

    /** Đặt {@code m_SortingOrder} cho SpriteRenderer của GameObject tên {@code goName}. */
    public static Sorting setGameObjectSpriteSorting(String prefabText, String goName, int newOrder) {
        record Doc(String fid, int cls, int start, int end, String body) { }
        List<Doc> docs = new ArrayList<>();
        // \r?\n BẮT BUỘC: Player.prefab của client là CRLF 100% (48.619 dòng), để \n trần thì
        // KHÔNG khớp document nào và hàm âm thầm trả về "không thấy GameObject".
        Matcher m = Pattern.compile("--- !u!(\\d+) &(\\d+)\\r?\\n(.*?)(?=\\r?\\n--- |\\Z)", Pattern.DOTALL)
                .matcher(prefabText);
        while (m.find()) {
            docs.add(new Doc(m.group(2), Integer.parseInt(m.group(1)), m.start(3), m.end(3), m.group(3)));
        }
        List<String> targets = new ArrayList<>();
        for (Doc d : docs) {
            if (d.cls() != 1) continue;                       // 1 = GameObject
            Matcher nm = Pattern.compile("^\\s*m_Name:\\s*(.+)$", Pattern.MULTILINE).matcher(d.body());
            if (nm.find() && nm.group(1).trim().equals(goName)) {
                Matcher cm = Pattern.compile("-\\s*component:\\s*\\{fileID:\\s*(\\d+)\\}").matcher(d.body());
                while (cm.find()) targets.add(cm.group(1));
                break;
            }
        }
        if (targets.isEmpty()) return new Sorting(prefabText, null);
        for (Doc d : docs) {
            if (d.cls() != 212 || !targets.contains(d.fid())) continue;   // 212 = SpriteRenderer
            Matcher om = Pattern.compile("^(\\s*m_SortingOrder:\\s*)(-?\\d+)", Pattern.MULTILINE).matcher(d.body());
            if (!om.find()) return new Sorting(prefabText, null);
            int old = Integer.parseInt(om.group(2));
            String newBody = d.body().substring(0, om.start()) + om.group(1) + newOrder
                    + d.body().substring(om.end());
            return new Sorting(prefabText.substring(0, d.start()) + newBody + prefabText.substring(d.end()), old);
        }
        return new Sorting(prefabText, null);
    }

    // ════════════════════ .meta của frame: PPU + pivot ════════════════════

    /** {@code spritePivot} (0..1, gốc dưới-trái). null nếu .meta không có. */
    public static double[] readSpritePivot(String metaText) {
        Matcher m = Pattern.compile("spritePivot:\\s*\\{x:\\s*([0-9.\\-eE]+),\\s*y:\\s*([0-9.\\-eE]+)\\}")
                .matcher(metaText);
        return m.find() ? new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))} : null;
    }

    /** {@code spriteMode}: 1 = Single (đúng cho hào quang), 2 = Multiple (sai — game không hiện). */
    public static Integer readSpriteMode(String metaText) {
        Matcher m = Pattern.compile("^\\s*spriteMode:\\s*(\\d+)", Pattern.MULTILINE).matcher(metaText);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** {@code spritePixelsToUnits} (PPU). null nếu không có. */
    public static Double readSpritePpu(String metaText) {
        Matcher m = Pattern.compile("^\\s*spritePixelsToUnits:\\s*([0-9.]+)", Pattern.MULTILINE).matcher(metaText);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /**
     * Đặt {@code spritePivot} — pivot quyết định VỊ TRÍ aura trong game (điểm pivot đặt tại gốc
     * spine / chân nhân vật). Bắt buộc kèm {@code alignment: 9} (custom), không có thì Unity bỏ qua
     * pivot và dùng preset.
     */
    public static String setSpritePivot(String metaText, double x, double y) {
        String t = Pattern.compile("(spritePivot:\\s*\\{x:\\s*)[0-9.\\-eE]+(,\\s*y:\\s*)[0-9.\\-eE]+(\\})")
                .matcher(metaText).replaceFirst("$1" + Matcher.quoteReplacement(fmt(x))
                        + "$2" + Matcher.quoteReplacement(fmt(y)) + "$3");
        return Pattern.compile("(^\\s*alignment:\\s*)\\d+", Pattern.MULTILINE).matcher(t).replaceFirst("$19");
    }

    /** Đặt PPU mới (PPU nhỏ hơn ⇒ sprite TO hơn trong game). */
    public static String setSpritePpu(String metaText, double newPpu) {
        return Pattern.compile("(^\\s*spritePixelsToUnits:\\s*)[0-9.]+", Pattern.MULTILINE)
                .matcher(metaText).replaceFirst("$1" + Matcher.quoteReplacement(fmt(newPpu)));
    }

    /**
     * Lấy cấu hình import từ 1 .meta mẫu (frame Single chuẩn) nhưng GIỮ guid cho trước — dùng để
     * sửa frame bị import Multiple về Single mà .anim vẫn trỏ đúng.
     */
    public static String retargetMeta(String templateText, String guid) {
        String t = Pattern.compile("^guid:\\s*[0-9a-fA-F]{32}\\s*$", Pattern.MULTILINE)
                .matcher(templateText).replaceFirst("guid: " + guid);
        return Pattern.compile("(spriteID:\\s*)[0-9a-fA-F]{32}").matcher(t)
                .replaceFirst("$1" + newGuid());
    }

    /** .meta mới + guid mới. */
    public record MetaNew(String text, String guid) { }

    /** .meta mới cho 1 frame PNG: sao cấu hình import từ mẫu, chỉ thay guid + spriteID. */
    public static MetaNew cloneMeta(String templateText) {
        String g = newGuid();
        String t = Pattern.compile("^guid:\\s*[0-9a-fA-F]{32}\\s*$", Pattern.MULTILINE)
                .matcher(templateText).replaceFirst("guid: " + g);
        t = Pattern.compile("(spriteID:\\s*)[0-9a-fA-F]{32}").matcher(t).replaceFirst("$1" + newGuid());
        return new MetaNew(t, g);
    }

    /** .meta cho một thư mục asset mới. */
    public static MetaNew buildFolderMeta() {
        String g = newGuid();
        return new MetaNew("fileFormatVersion: 2\n"
                + "guid: " + g + "\n"
                + "folderAsset: yes\n"
                + "DefaultImporter:\n"
                + "  externalObjects: {}\n"
                + "  userData:\n"
                + "  assetBundleName:\n"
                + "  assetBundleVariant:\n", g);
    }

    // ════════════════════ dò đường trong client ════════════════════

    /** Từ một path bất kỳ trong project, tìm {@code Assets/Textures/GamePlay/HaoQuang}. */
    public static Path findHaoQuangTexturesDir(Path start) {
        for (Path p = start; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("Assets")) {
                Path d = p.resolve("Textures").resolve("GamePlay").resolve("HaoQuang");
                return Files.isDirectory(d) ? d : null;
            }
        }
        return null;
    }

    /** Tên các folder hào quang có sẵn (có chứa .png). */
    public static List<String> listExistingAuras(Path texDir) {
        List<String> out = new ArrayList<>();
        if (texDir == null || !Files.isDirectory(texDir)) return out;
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(texDir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isDirectory(p)) dirs.add(p);
        } catch (IOException ignored) { return out; }
        dirs.sort(BY_NAME);
        for (Path d : dirs) {
            try (Stream<Path> s = Files.list(d)) {
                if (s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))) {
                    out.add(d.getFileName().toString());
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** Các đường dẫn trong client, dò từ tổ tiên tên {@code Assets}. Thiếu key = file không tồn tại. */
    public static Map<String, Path> detectClientPaths(Path anyPathInProject) {
        Map<String, Path> out = new LinkedHashMap<>();
        Path assets = null;
        for (Path p = anyPathInProject; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("Assets")) { assets = p; break; }
        }
        if (assets == null) return out;
        Map<String, Path> cand = new LinkedHashMap<>();
        cand.put("controller", assets.resolve("AssetBundles/Resource/HaoQuang/HaoQuang.controller"));
        cand.put("enum", assets.resolve("Scripts/Features/Ingame/Player/Enum/Enum_HaoQuang.cs"));
        cand.put("enum_dacbiet", assets.resolve("Scripts/Features/Ingame/Player/Enum/Enum_HaoQuangDacBiet.cs"));
        cand.put("actorvisual", assets.resolve(
                "Scripts/Features/Ingame/Player/Hierarchy_1/Hierarchy_2/Hierarchy_3/ActorVisual.cs"));
        cand.put("anim_out_dir", assets.resolve("AssetBundles/Resource/HaoQuang"));
        cand.put("prefab", assets.resolve("Resources/Ingame/Player/Player.prefab"));
        for (Map.Entry<String, Path> e : cand.entrySet()) {
            if (Files.exists(e.getValue())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** .meta mẫu: ưu tiên 1 frame Single có sẵn trong project; không có thì dùng mẫu nhúng sẵn. */
    public record Template(String text, String source) { }

    public static Template templateMetaText(Path texDir) {
        Path p = findTemplateMeta(texDir);
        if (p != null) {
            try { return new Template(read(p), p.getParent().getFileName().toString()); }
            catch (Exception ignored) { }
        }
        return new Template(DEFAULT_SINGLE_META, "mẫu mặc định");
    }

    private static Path findTemplateMeta(Path texDir) {
        if (texDir == null || !Files.isDirectory(texDir)) return null;
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(texDir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isDirectory(p)) dirs.add(p);
        } catch (IOException ignored) { return null; }
        dirs.sort(BY_NAME);
        for (Path d : dirs) {
            List<Path> metas = new ArrayList<>();
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().toLowerCase().endsWith(".png.meta")) metas.add(p);
                }
            } catch (IOException ignored) { continue; }
            metas.sort(BY_NAME);
            for (Path p : metas) {
                Integer mode = readSpriteMode(read(p));
                if (mode != null && mode == 1) return p;
            }
        }
        return null;
    }

    private static String replaceFirstLiteral(String text, String find, String repl) {
        int i = text.indexOf(find);
        return i < 0 ? text : text.substring(0, i) + repl + text.substring(i + find.length());
    }

    /**
     * Mẫu .meta Sprite Single mặc định — dùng khi project chưa có frame Single nào để làm mẫu
     * (máy team mới, hoặc chưa dò ra thư mục HaoQuang). guid/spriteID ở đây chỉ là chỗ giữ, luôn bị
     * thay khi clone / retarget.
     */
    public static final String DEFAULT_SINGLE_META = """
            fileFormatVersion: 2
            guid: 92c2c89b255564286a8d44da1969e594
            TextureImporter:
              internalIDToNameTable: []
              externalObjects: {}
              serializedVersion: 12
              mipmaps:
                mipMapMode: 0
                enableMipMap: 0
                sRGBTexture: 1
                linearTexture: 0
                fadeOut: 0
                borderMipMap: 0
                mipMapsPreserveCoverage: 0
                alphaTestReferenceValue: 0.5
                mipMapFadeDistanceStart: 1
                mipMapFadeDistanceEnd: 3
              bumpmap:
                convertToNormalMap: 0
                externalNormalMap: 0
                heightScale: 0.25
                normalMapFilter: 0
                flipGreenChannel: 0
              isReadable: 0
              streamingMipmaps: 0
              streamingMipmapsPriority: 0
              vTOnly: 0
              ignoreMipmapLimit: 0
              grayScaleToAlpha: 0
              generateCubemap: 6
              cubemapConvolution: 0
              seamlessCubemap: 0
              textureFormat: 1
              maxTextureSize: 2048
              textureSettings:
                serializedVersion: 2
                filterMode: 1
                aniso: 1
                mipBias: 0
                wrapU: 1
                wrapV: 1
                wrapW: 1
              nPOTScale: 0
              lightmap: 0
              compressionQuality: 50
              spriteMode: 1
              spriteExtrude: 1
              spriteMeshType: 0
              alignment: 9
              spritePivot: {x: 0.5, y: 0.1}
              spritePixelsToUnits: 100
              spriteBorder: {x: 0, y: 0, z: 0, w: 0}
              spriteGenerateFallbackPhysicsShape: 1
              alphaUsage: 1
              alphaIsTransparency: 1
              spriteTessellationDetail: -1
              textureType: 8
              textureShape: 1
              singleChannelComponent: 0
              flipbookRows: 1
              flipbookColumns: 1
              maxTextureSizeSet: 0
              compressionQualitySet: 0
              textureFormatSet: 0
              ignorePngGamma: 0
              applyGammaDecoding: 0
              swizzle: 50462976
              cookieLightType: 0
              platformSettings:
              - serializedVersion: 3
                buildTarget: DefaultTexturePlatform
                maxTextureSize: 2048
                resizeAlgorithm: 0
                textureFormat: -1
                textureCompression: 1
                compressionQuality: 50
                crunchedCompression: 0
                allowsAlphaSplitting: 0
                overridden: 0
                ignorePlatformSupport: 0
                androidETC2FallbackOverride: 0
                forceMaximumCompressionQuality_BC6H_BC7: 0
              spriteSheet:
                serializedVersion: 2
                sprites: []
                outline: []
                physicsShape: []
                bones: []
                spriteID: a3a2b351538b46b5b811d24ad1654b6b
                internalID: 1537655665
                vertices: []
                indices:
                edges: []
                weights: []
                secondaryTextures: []
                nameFileIdTable: {}
              mipmapLimitGroupName:
              pSDRemoveMatte: 0
              userData:
              assetBundleName:
              assetBundleVariant:
            """;
}
