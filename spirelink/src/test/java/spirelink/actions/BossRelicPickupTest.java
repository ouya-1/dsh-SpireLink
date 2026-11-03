package spirelink.actions;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * #52: 领 Boss 遗物时「需要选牌」的效果不生效（空鸟笼不弹网格）。
 *
 * <p>根因在屏幕时序上，所以时序被抽成纯函数 {@link ActionExecutor#planRelicPickup(String, boolean)}
 * 单独钉住：这条链真的跑起来要开游戏，而顺序错了的后果（网格被关掉 / 遗物屏又弹回来）在单测里
 * 用一个 list 就能表达。
 *
 * <p>两条来自反汇编的规矩：
 * <ul>
 *   <li>已经有屏幕时，必须先关再拿遗物。{@code AbstractDungeon.closeCurrentScreen()} 在
 *       {@code previousScreen} 非空时会把那块屏「还原」回来，而 {@code EmptyCage.onEquip()} 在
 *       {@code isScreenUp} 为真时恰好把 BOSS_REWARD 写进 {@code previousScreen}——先开网格再关屏
 *       就会把遗物屏弹回来；</li>
 *   <li>遗物的效果就是那块网格时，拿完之后必须 {@code KEEP_GRID}：任何一次收尾
 *       {@code closeCurrentScreen()} 都会连同玩家的选牌一起把效果收掉。</li>
 * </ul>
 */
public class BossRelicPickupTest {

    private static List<ActionExecutor.RelicPickupStep> plan(String screen, boolean grid) {
        return ActionExecutor.planRelicPickup(screen, grid);
    }

    // ------------------------------------------------------------------ 屏幕时序

    /** 遗物屏开着、遗物不需要网格：先关屏，再 instantObtain。 */
    @Test
    public void openScreenIsClosedBeforeAnOrdinaryRelicIsTaken() {
        assertEquals(Arrays.asList(
                        ActionExecutor.RelicPickupStep.CLOSE_SCREEN,
                        ActionExecutor.RelicPickupStep.OBTAIN),
                plan("BOSS_REWARD", false));
    }

    /** 没有屏幕就没什么可关的，直接拿。 */
    @Test
    public void noScreenTakesTheRelicDirectly() {
        assertEquals(Arrays.asList(ActionExecutor.RelicPickupStep.OBTAIN), plan("NONE", false));
        // AbstractDungeon.screen 在主菜单/读档途中可以是 null —— 和 NONE 同义。
        assertEquals(Arrays.asList(ActionExecutor.RelicPickupStep.OBTAIN), plan(null, false));
    }

    /** 空鸟笼/星盘：遗物屏必须先关，然后拿遗物，最后把网格留着（这就是 #52 的修复形态）。 */
    @Test
    public void gridRelicClosesTheScreenThenKeepsTheGrid() {
        assertEquals(Arrays.asList(
                        ActionExecutor.RelicPickupStep.CLOSE_SCREEN,
                        ActionExecutor.RelicPickupStep.OBTAIN,
                        ActionExecutor.RelicPickupStep.KEEP_GRID),
                plan("BOSS_REWARD", true));
    }

    /** 屏幕已经归一成 NONE（例如上一次点击已经把它关掉）：只拿 + 留网格。 */
    @Test
    public void gridRelicWithoutAScreenOnlyTakesAndKeeps() {
        assertEquals(Arrays.asList(
                        ActionExecutor.RelicPickupStep.OBTAIN,
                        ActionExecutor.RelicPickupStep.KEEP_GRID),
                plan("NONE", true));
        assertEquals(Arrays.asList(
                        ActionExecutor.RelicPickupStep.OBTAIN,
                        ActionExecutor.RelicPickupStep.KEEP_GRID),
                plan(null, true));
    }

    /** 「不是 NONE 就是屏幕」——地图/网格/奖励屏都算，不能只认 BOSS_REWARD。 */
    @Test
    public void anyOtherScreenIsAlsoClosedFirst() {
        for (String screen : new String[]{"BOSS_REWARD", "GRID", "MAP", "COMBAT_REWARD", "SHOP"}) {
            assertEquals(screen, ActionExecutor.RelicPickupStep.CLOSE_SCREEN,
                    plan(screen, true).get(0));
            assertEquals(screen, ActionExecutor.RelicPickupStep.CLOSE_SCREEN,
                    plan(screen, false).get(0));
        }
    }

    /**
     * 回归点：旧代码是在屏幕还开着的时候 instantObtain，然后再 closeCurrentScreen ——
     * 也就是 OBTAIN 排在 CLOSE_SCREEN 之前。这条断言把那个顺序钉死为不可能。
     */
    @Test
    public void theOldTakeThenCloseOrderIsImpossible() {
        for (String screen : new String[]{"BOSS_REWARD", "GRID", "MAP"}) {
            for (boolean grid : new boolean[]{true, false}) {
                List<ActionExecutor.RelicPickupStep> steps = plan(screen, grid);
                assertTrue(screen + " grid=" + grid,
                        steps.indexOf(ActionExecutor.RelicPickupStep.CLOSE_SCREEN)
                                < steps.indexOf(ActionExecutor.RelicPickupStep.OBTAIN));
            }
        }
    }

    /** KEEP_GRID 只会出现在不需要关屏的时候，而且永远排在最后。 */
    @Test
    public void keepGridIsAlwaysLastAndOnlyForGridRelics() {
        for (String screen : new String[]{"NONE", "BOSS_REWARD"}) {
            List<ActionExecutor.RelicPickupStep> noGrid = plan(screen, false);
            assertFalse(screen, noGrid.contains(ActionExecutor.RelicPickupStep.KEEP_GRID));

            List<ActionExecutor.RelicPickupStep> grid = plan(screen, true);
            assertEquals(screen, ActionExecutor.RelicPickupStep.KEEP_GRID,
                    grid.get(grid.size() - 1));
        }
    }

    // ------------------------------------------------------------------ 哪些遗物会开网格

    /** 反汇编确认：EmptyCage / Astrolabe 的 onEquip() 直接调 GridCardSelectScreen.open(...)。 */
    @Test
    public void emptyCageAndAstrolabeOpenAGridOnEquip() {
        assertTrue(ActionExecutor.relicOpensGridOnEquip("Empty Cage"));
        assertTrue(ActionExecutor.relicOpensGridOnEquip("Astrolabe"));
    }

    /** 同一份反汇编里另外几个 onEquip 会开网格的遗物（瓶子/潘多拉/召唤铃铛/多利镜）。 */
    @Test
    public void theOtherVanillaGridRelicsAreRecognisedToo() {
        for (String id : new String[]{"Pandora's Box", "Calling Bell", "DollysMirror",
                "Bottled Flame", "Bottled Lightning", "Bottled Tornado"}) {
            assertTrue(id, ActionExecutor.relicOpensGridOnEquip(id));
        }
    }

    /** 普通 Boss 遗物不受影响：它们 onEquip() 里没有网格，走原来的原生点击路径。 */
    @Test
    public void ordinaryBossRelicsDoNotOpenAGrid() {
        for (String id : new String[]{"Sozu", "Black Blood", "Coffee Dripper", "Runic Dome",
                "Snecko Eye", "Philosopher's Stone", "Busted Crown", "Ectoplasm",
                "Fusion Hammer", "Velvet Choker", "Runic Pyramid", "Tiny House"}) {
            assertFalse(id, ActionExecutor.relicOpensGridOnEquip(id));
        }
        assertFalse(ActionExecutor.relicOpensGridOnEquip(null));
        assertFalse(ActionExecutor.relicOpensGridOnEquip(""));
        assertFalse(ActionExecutor.relicOpensGridOnEquip("Not A Relic"));
    }
}
