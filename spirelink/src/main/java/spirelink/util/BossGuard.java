package spirelink.util;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * Test-mode safety net for the act boss list.
 *
 * <p>{@code MonsterRoomBoss.onPlayerEntry()} does {@code AbstractDungeon.bossList.remove(0)} (it logs
 * the boss it is about to fight) and dies with an IndexOutOfBoundsException when that list is empty.
 * The dev tools can jump into an act context that never generated a boss, and events such as
 * SecretPortal teleport into the boss room, so an IOOBE there takes the whole render loop down.
 */
public final class BossGuard {

    private BossGuard() {
    }

    /** True when a boss room can be entered without crashing. */
    public static boolean bossAvailable() {
        try {
            return AbstractDungeon.bossList != null && !AbstractDungeon.bossList.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Seeds one boss id when the list is empty. {@code bossList} is an {@code ArrayList<String>} of
     * boss class names; {@code getBoss()} builds the actual monster group, so a placeholder id is
     * enough to keep {@code remove(0)} safe. Test mode only.
     */
    public static boolean ensureBossList() {
        try {
            if (AbstractDungeon.bossList == null) {
                return false;
            }
            if (!AbstractDungeon.bossList.isEmpty()) {
                return true;
            }
            String boss = AbstractDungeon.actNum <= 1 ? "Hexaghost"
                    : AbstractDungeon.actNum == 2 ? "Champ" : "AwakenedOne";
            AbstractDungeon.bossList.add(boss);
            Diag.warn("dev: bossList 为空，已补入占位 Boss " + boss + "（act "
                    + AbstractDungeon.actNum + "），避免 onPlayerEntry 的 remove(0) 崩溃");
            return true;
        } catch (Throwable t) {
            Diag.warn("dev: 补 bossList 失败: " + t);
            return false;
        }
    }
}
