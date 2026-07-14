package com.apex.maptool.unity;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.spine.SpineCharacter;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolve icon thật cho marker:
 *  - Enemy: Textures/GamePlay/IconEnemy/{infoId}.png  (client GetIconIconEnemy)
 *  - NPC:   Resource/Npc/AvataSkin|IconSkin/{id}.png  (theo skin id — npc_info.id thường KHÔNG khớp → fallback null)
 */
public final class SpriteResolver {
    private final ToolConfig cfg;
    private final TextureCache tex;
    private Map<Integer, Integer> enemyIdToSpin = new HashMap<>();
    private Map<Integer, Integer> enemyIdToType = new HashMap<>();
    private Map<Integer, Integer> npcIdToModel = new HashMap<>();
    // spin id quái bay (GameLogic._flyEnemyTypeSpineIdList)
    private static final Set<Integer> FLY_SPIN = new HashSet<>(java.util.Arrays.asList(
            7, 8, 9, 10, 11, 12, 21, 25, 31, 32, 33, 37, 43, 49, 50, 69, 75, 79));

    public void setEnemyTypeMap(Map<Integer, Integer> m) { this.enemyIdToType = m; }

    /** Đứng im: type IDLE_ENEMY(0) | TRUNG_MABU(9) | spin 76. (≤1 patrol point xử lý ở canvas) */
    public boolean enemyStatic(int infoId) {
        int type = enemyIdToType.getOrDefault(infoId, 1);
        int spin = enemyIdToSpin.getOrDefault(infoId, infoId);
        return type == 0 || type == 9 || spin == 76;
    }
    /** Quái bay: type FLY_ENEMY(6) | spin trong fly list. */
    public boolean enemyFly(int infoId) {
        int type = enemyIdToType.getOrDefault(infoId, 1);
        int spin = enemyIdToSpin.getOrDefault(infoId, infoId);
        return type == 6 || FLY_SPIN.contains(spin);
    }
    /** Range patrol: type≤1→3, boss(2,3,7)→5, else→2. */
    public int enemyStep(int infoId) {
        int type = enemyIdToType.getOrDefault(infoId, 1);
        if (type <= 1) return 3;
        if (type == 2 || type == 3 || type == 7) return 5;
        return 2;
    }

    public SpriteResolver(ToolConfig cfg, TextureCache tex) {
        this.cfg = cfg;
        this.tex = tex;
    }

    /** Map enemy_info.id → spin_id (visual id). Icon/spine keyed theo spin_id. */
    public void setEnemySpinMap(Map<Integer, Integer> m) { this.enemyIdToSpin = m; }
    /** Map npcs.id → model_id (visual id). */
    public void setNpcModelMap(Map<Integer, Integer> m) { this.npcIdToModel = m; }

    // ─── Spine character (animated) ────────────────────────────
    private final Map<Path, SpineCharacter> spineCache = new HashMap<>();
    private final Set<Path> spineTried = new HashSet<>();

    public SpineCharacter spineEnemy(int infoId) {
        int spin = enemyIdToSpin.getOrDefault(infoId, infoId);
        return spine(cfg.assetsRoot().resolve("AssetBundles/Resource/Enemy/" + spin));
    }

    public SpineCharacter spineNpc(int id) {
        int model = npcIdToModel.getOrDefault(id, id);
        return spine(cfg.assetsRoot().resolve("AssetBundles/Resource/Npc/" + model));
    }

    /** Spine nhân vật player (mặc định skin 22 = DefaultPlayerPathSpine) để căn điểm rơi. */
    public SpineCharacter spinePlayer(int skinId) {
        return spine(cfg.assetsRoot().resolve("AssetBundles/Resource/Player/" + skinId));
    }
    public SpineCharacter spinePlayer() { return spinePlayer(22); }

    private SpineCharacter spine(Path folder) {
        if (spineCache.containsKey(folder)) return spineCache.get(folder);
        if (spineTried.contains(folder)) return null;
        spineTried.add(folder);
        SpineCharacter sc = SpineCharacter.load(folder);
        if (sc != null) spineCache.put(folder, sc);
        return sc;
    }

    public BufferedImage enemy(int infoId) {
        // Visual theo spin_id (enemy_info.spin_id), KHÔNG phải infoId.
        int spin = enemyIdToSpin.getOrDefault(infoId, infoId);
        Path p = cfg.assetsRoot().resolve("Textures/GamePlay/IconEnemy/" + spin + ".png");
        return Files.exists(p) ? tex.image(p) : null;
    }

    public BufferedImage npc(int id) {
        // Visual theo model_id (npcs.model_id), KHÔNG phải npc id.
        int model = npcIdToModel.getOrDefault(id, id);
        Path big = cfg.assetsRoot().resolve("AssetBundles/Resource/NpcBigIcon/" + model + ".png");
        if (Files.exists(big)) return tex.image(big);
        Path base = cfg.assetsRoot().resolve("AssetBundles/Resource/Npc");
        Path a = base.resolve("AvataSkin/" + model + ".png");
        if (Files.exists(a)) return tex.image(a);
        Path b = base.resolve("IconSkin/" + model + ".png");
        if (Files.exists(b)) return tex.image(b);
        return null; // không có icon → caller fallback dot+tên
    }
}
