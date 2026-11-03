package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.MonsterHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Global safety net for the vanilla monster pool (issues #64 / #73, follow-up of task-19).
 *
 * <h3>Why this exists</h3>
 * <p>{@code Exordium/TheCity/TheBeyond.generateStrongEnemies(int)} calls {@code generateExclusions()}
 * <b>before</b> it fills anything, and {@code generateExclusions()} starts with</p>
 * <pre>
 *   String last = AbstractDungeon.monsterList.get(AbstractDungeon.monsterList.size() - 1);
 * </pre>
 * <p>— no empty check (verified with {@code javap} on {@code lib/desktop-1.0.jar}, Exordium.java:192).
 * Both call sites are reached exactly when the pool is already empty:</p>
 * <ul>
 *   <li>{@code AbstractDungeon.getMonsterForRoomCreation()} line 2340
 *       ({@code if (monsterList.isEmpty()) generateStrongEnemies(12);}) — this is the frame QA hit
 *       twice, the exception escapes into the render loop and freezes the whole run;</li>
 *   <li>{@code AbstractDungeon.nextRoomTransition(SaveFile)} — the same shape on the save-load path.</li>
 * </ul>
 * <p>Normal play never gets there because {@code generateMonsters()} seeds ~25 entries at act start
 * and {@code nextRoomTransition} consumes one per monster room. Anything that empties the pool
 * earlier (a dev teleport, a mod, a half-applied restore) turns the next monster room into a
 * {@code ArrayIndexOutOfBoundsException: -1} that no {@code try/catch} can recover from.</p>
 *
 * <h3>What this does</h3>
 * <p>Before either method runs, if {@code AbstractDungeon.monsterList} is empty it is seeded with a
 * few real weak encounters of the current act. Vanilla then takes its normal path
 * ({@code monsterList.get(0)}); no vanilla flow is changed and the guard is inert whenever the pool
 * is healthy (which is always, in an unmodified run).</p>
 *
 * <p>Ids come from {@code MonsterHelper.*_ENC}; those are compile-time string constants, so seeding
 * cannot pull in a class that is not loaded yet. They are also the values vanilla's own weak pools
 * roll (see {@code Exordium.generateWeakEnemies}).</p>
 */
public final class MonsterPoolGuardPatch {

    /** Act 1 weak encounters (the same ids {@code Exordium.generateWeakEnemies} rolls). */
    static final String[] ACT1_POOL = {
            MonsterHelper.CULTIST_ENC,
            MonsterHelper.JAW_WORM_ENC,
            MonsterHelper.TWO_LOUSE_ENC,
            MonsterHelper.SMALL_SLIMES_ENC,
    };

    /** Act 2 weak encounters ({@code TheCity.generateWeakEnemies}). */
    static final String[] ACT2_POOL = {
            MonsterHelper.CHOSEN_ENC,
            MonsterHelper.SHELL_PARASITE_ENC,
            MonsterHelper.SPHERE_GUARDIAN_ENC,
            MonsterHelper.THREE_BYRDS_ENC,
    };

    /** Act 3 weak encounters ({@code TheBeyond.generateWeakEnemies}). */
    static final String[] ACT3_POOL = {
            MonsterHelper.ORB_WALKER_ENC,
            MonsterHelper.THREE_DARKLINGS_ENC,
            MonsterHelper.SPIRE_GROWTH_ENC,
            MonsterHelper.TRANSIENT_ENC,
    };

    /** Rotates the order so a repeated refill does not replay the same four fights in the same order. */
    private static int seedRound;

    private MonsterPoolGuardPatch() {
    }

    /**
     * Pure: which pool belongs to an act.
     *
     * <p>Act 4 ({@code TheEnding}) and anything unexpected reuse the act-3 pool: the ids only have to
     * be encounters {@code MonsterHelper} understands, and the guard only fires on an already
     * corrupted run.</p>
     */
    static String[] poolFor(int actNum) {
        if (actNum <= 1) {
            return ACT1_POOL;
        }
        if (actNum == 2) {
            return ACT2_POOL;
        }
        return ACT3_POOL;
    }

    /** Pure: the pool needs seeding only when it exists and is empty. */
    static boolean needsSeed(List<String> monsterList) {
        return monsterList != null && monsterList.isEmpty();
    }

    /**
     * Pure apart from the list it is handed: seeds {@code monsterList} when it is empty.
     *
     * @return how many ids were added (0 when nothing had to be done)
     */
    static int seedIfEmpty(List<String> monsterList, String[] candidates, int offset) {
        if (!needsSeed(monsterList) || candidates == null || candidates.length == 0) {
            return 0;
        }
        int added = 0;
        for (int i = 0; i < candidates.length; i++) {
            String id = candidates[Math.floorMod(offset + i, candidates.length)];
            if (id != null && !id.isEmpty()) {
                monsterList.add(id);
                added++;
            }
        }
        return added;
    }

    /**
     * Runtime entry point used by the patches below.
     *
     * @return true when the pool was empty and had to be refilled
     */
    static boolean ensureSeeded() {
        try {
            if (AbstractDungeon.monsterList == null) {
                // Vanilla dereferences it unconditionally right after this point; an empty list is the
                // state vanilla can at least still recover from with our seeding below.
                AbstractDungeon.monsterList = new ArrayList<String>();
            }
            if (!needsSeed(AbstractDungeon.monsterList)) {
                return false;
            }
            int act = AbstractDungeon.actNum;
            int added = seedIfEmpty(AbstractDungeon.monsterList, poolFor(act), seedRound++);
            spirelink.util.Diag.warn("CrashGuard: monsterList 空表（act=" + act + "），已补种 "
                    + added + " 条本幕遭遇 " + AbstractDungeon.monsterList
                    + "；否则 vanilla generateExclusions() 会 monsterList.get(-1) 直接 AIOOBE，整局卡死");
            return added > 0;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("CrashGuard: 补种 monsterList 失败: " + t);
            return false;
        }
    }

    /** The crash site QA hit twice: MonsterRoom.onPlayerEntry -> getMonsterForRoomCreation. */
    @SpirePatch(clz = AbstractDungeon.class, method = "getMonsterForRoomCreation")
    public static class OnGetMonsterForRoomCreation {

        @SpirePrefixPatch
        public static void Prefix(AbstractDungeon __instance) {
            ensureSeeded();
        }
    }

    /** The save-load path calls the same generator on the same pool. */
    @SpirePatch(clz = AbstractDungeon.class, method = "nextRoomTransition",
            paramtypez = {com.megacrit.cardcrawl.saveAndContinue.SaveFile.class})
    public static class OnNextRoomTransitionFromSave {

        @SpirePrefixPatch
        public static void Prefix(AbstractDungeon __instance,
                com.megacrit.cardcrawl.saveAndContinue.SaveFile save) {
            ensureSeeded();
        }
    }
}
