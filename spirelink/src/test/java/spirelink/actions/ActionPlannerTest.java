package spirelink.actions;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure helpers of the P1 action layer (no game objects touched). */
public class ActionPlannerTest {

    @Test
    public void labelsStripGameMarkup() {
        assertEquals("造成 6 点伤害", ActionPlanner.clean("[造成 #b6 点伤害]"));
        assertEquals("获得 5 点格挡", ActionPlanner.clean("获得 #g5 点格挡"));
        assertEquals("A B", ActionPlanner.clean("A NL B"));
        assertEquals("", ActionPlanner.clean(null));
    }

    @Test
    public void longLabelsAreTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('长');
        }
        String cleaned = ActionPlanner.clean(sb.toString());
        assertTrue(cleaned.length() <= 40);
        assertTrue(cleaned.endsWith("…"));
    }

    @Test
    public void modeLabelsAreChinese() {
        assertEquals("标准模式", ActionPlanner.modeLabel("standard"));
        assertEquals("每日挑战", ActionPlanner.modeLabel("daily"));
        assertEquals("自定模式", ActionPlanner.modeLabel("custom"));
    }

    // ---------------------------------------------------------------- boss cleared (#42)

    /**
     * Issue #42: after the act boss dies the ui is {@code boss_cleared}, and the only way out is
     * {@code proceed}. It must come from the dedicated case, not from the default branch's
     * {@code canProceed() || screenUp} fallback: the vanilla proceed button is hidden for a beat
     * while the reward pop-out animation runs, and the executor advances deterministically anyway.
     *
     * <p>Touches no game object, so it also proves the case cannot silently depend on one.
     */
    @Test
    public void bossClearedOffersExactlyOneProceedWithoutTouchingTheGame() {
        List<ActionSpec> actions = ActionPlanner.actionsFor("boss_cleared");
        assertEquals("boss_cleared 只能给一条出口", 1, actions.size());
        ActionSpec only = actions.get(0);
        assertEquals("a1", only.id);
        assertEquals(ActionTypes.PROCEED, only.type);
        assertEquals("打开地图", only.label);
        assertEquals("{}", only.payload.toString());
    }

    // ---------------------------------------------------------------- char select (#49)

    /** The four unlocked characters in vanilla order; Chinese names so labels are checkable. */
    private static final String[][] ROSTER = {
            {"IRONCLAD", "铁甲战士"},
            {"THE_SILENT", "静默猎手"},
            {"DEFECT", "故障机器人"},
            {"WATCHER", "观者"},
    };

    private static List<ActionPlanner.CharOption> roster(String selectedId) {
        List<ActionPlanner.CharOption> out = new ArrayList<ActionPlanner.CharOption>();
        for (String[] row : ROSTER) {
            out.add(new ActionPlanner.CharOption(row[0], row[1], row[0].equals(selectedId), false));
        }
        return out;
    }

    private static List<ActionSpec> plan(List<ActionPlanner.CharOption> roster, int cap,
            int ascension) {
        List<ActionSpec> out = new ArrayList<ActionSpec>();
        ActionPlanner.charSelectActions(out, roster, cap, ascension);
        return out;
    }

    private static int indexOfType(List<ActionSpec> actions, String type) {
        for (int i = 0; i < actions.size(); i++) {
            if (type.equals(actions.get(i).type)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void charSelectWithoutSelectionKeepsTheOldShape() {
        List<ActionSpec> actions = plan(roster(null), 20, 0);
        // No pick yet: 4 choose_character + back, no embark and no ascension ladder.
        assertEquals(5, actions.size());
        assertEquals(-1, indexOfType(actions, ActionTypes.EMBARK));
        for (int i = 0; i < ROSTER.length; i++) {
            ActionSpec spec = actions.get(i);
            assertEquals("a" + (i + 1), spec.id);
            assertEquals(ActionTypes.CHOOSE_CHARACTER, spec.type);
            assertTrue(spec.label.startsWith("选择"));
            assertEquals(ROSTER[i][0], spec.payload.get("character").getAsString());
            assertFalse("未选中的角色不该带 selected", spec.payload.has("selected"));
        }
        assertEquals(ActionTypes.BACK, actions.get(4).type);
    }

    @Test
    public void charSelectWithSelectionPutsEmbarkFirstAndKeepsTheSelectedCharacter() {
        List<ActionSpec> actions = plan(roster("THE_SILENT"), 20, 0);
        // The whole point of #49: a naive harness always takes actions[0], and that must be embark.
        assertEquals(ActionTypes.EMBARK, actions.get(0).type);
        assertEquals(ActionTypes.BACK, actions.get(actions.size() - 1).type);

        ActionSpec kept = null;
        int characters = 0;
        for (ActionSpec spec : actions) {
            if (ActionTypes.CHOOSE_CHARACTER.equals(spec.type)) {
                characters++;
                if ("THE_SILENT".equals(spec.payload.get("character").getAsString())) {
                    kept = spec;
                }
            }
        }
        assertEquals("四个角色都要留着，已选的那个不能消失", 4, characters);
        assertNotNull("已选角色必须回显", kept);
        assertEquals("保持 静默猎手", kept.label);
        assertTrue(kept.payload.get("selected").getAsBoolean());

        int embark = indexOfType(actions, ActionTypes.EMBARK);
        assertTrue("升天档位仍然要有", indexOfType(actions, ActionTypes.CHOOSE_ASCENSION) > embark);
        for (int i = 0; i < actions.size(); i++) {
            if (ActionTypes.CHOOSE_ASCENSION.equals(actions.get(i).type)) {
                assertTrue("升天必须排在 embark 之后", i > embark);
            }
        }
    }

    @Test
    public void charSelectSkipsLockedCharacters() {
        List<ActionPlanner.CharOption> roster = new ArrayList<ActionPlanner.CharOption>();
        roster.add(new ActionPlanner.CharOption("IRONCLAD", "铁甲战士", false, false));
        roster.add(new ActionPlanner.CharOption("WATCHER", "观者", false, true));
        for (ActionSpec spec : plan(roster, 20, 0)) {
            assertFalse("没解锁的角色不能出现在候选里",
                    ActionTypes.CHOOSE_CHARACTER.equals(spec.type)
                            && "WATCHER".equals(spec.payload.get("character").getAsString()));
        }
    }

    /**
     * Regression for #49: a naive harness that only ever plays {@code actions[0]} must converge on
     * embark within 10 rounds.
     *
     * <p>The old planner looped here: pick IRONCLAD → it vanished from the list → the first action
     * became the next character → picking it brought IRONCLAD back → forever.
     */
    @Test
    public void naiveFirstActionHarnessConvergesToEmbark() {
        String selected = null;
        for (int round = 1; round <= 10; round++) {
            List<ActionSpec> actions = plan(roster(selected), 20, 0);
            assertFalse("第 " + round + " 轮没有动作", actions.isEmpty());
            ActionSpec first = actions.get(0);
            if (ActionTypes.EMBARK.equals(first.type)) {
                return;
            }
            assertEquals(ActionTypes.CHOOSE_CHARACTER, first.type);
            selected = first.payload.get("character").getAsString();
        }
        fail("naive harness 10 轮内没有收敛到 embark，当前选中=" + selected);
    }
}
