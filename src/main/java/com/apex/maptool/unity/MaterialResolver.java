package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .mat (Unity YAML) → texture png path.
 * Tìm _MainTex (hoặc texture đầu tiên) trong m_SavedProperties.m_TexEnvs.
 */
public final class MaterialResolver {

    private static final Pattern GUID = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");

    private final GuidIndex guidIndex;

    public MaterialResolver(GuidIndex guidIndex) {
        this.guidIndex = guidIndex;
    }

    /** matGuid → texture path (null nếu fail). */
    public Path resolveTexture(String matGuid) {
        Path mat = guidIndex.resolve(matGuid);
        if (mat == null || !Files.exists(mat)) return null;
        try {
            List<String> lines = Files.readAllLines(mat);
            // Ưu tiên _MainTex; fallback texture guid đầu tiên gặp.
            String firstTexGuid = null;
            boolean inMainTex = false;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("- _MainTex:") || line.equals("_MainTex:")) {
                    inMainTex = true;
                    continue;
                }
                if (inMainTex) {
                    Matcher m = GUID.matcher(line);
                    if (m.find()) {
                        Path tex = guidIndex.resolve(m.group(1));
                        if (tex != null) return tex;
                        inMainTex = false; // _MainTex rỗng → fallback
                    }
                    // _MainTex block thường: m_Texture: {fileID,guid}; nếu qua vài dòng ko thấy → thôi
                    if (line.startsWith("- ") && !line.contains("_MainTex")) inMainTex = false;
                }
                if (firstTexGuid == null) {
                    Matcher m = GUID.matcher(line);
                    // chỉ bắt guid trong context m_Texture
                    if (line.contains("m_Texture") && m.find()) {
                        firstTexGuid = m.group(1);
                    }
                }
            }
            if (firstTexGuid != null) {
                return guidIndex.resolve(firstTexGuid);
            }
        } catch (IOException e) {
            System.err.println("[MaterialResolver] read fail " + mat + ": " + e.getMessage());
        }
        return null;
    }
}
