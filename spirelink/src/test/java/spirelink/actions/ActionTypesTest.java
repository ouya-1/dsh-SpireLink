package spirelink.actions;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * B2-12: the docs/samples promise {@code UNKNOWN_ACTION_TYPE} for an unimplemented action type, but
 * every unknown type used to fall through the dispatch chain and come back as
 * {@code ILLEGAL_ACTION}. The gate now asks {@link ActionTypes#isKnown(String)} first.
 *
 * <p>{@code ALL} is a hand-written list, so this test walks the constants by reflection: a new action
 * type that is not added to it would be rejected as "unknown" — the worst possible failure mode for
 * an agent that just read {@code legal_actions}.</p>
 */
public class ActionTypesTest {

    @Test
    public void everyDeclaredConstantIsInAll() throws Exception {
        int checked = 0;
        for (Field field : ActionTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            String value = (String) field.get(null);
            checked++;
            assertTrue("常量 " + field.getName() + "（" + value + "）没进 ActionTypes.ALL："
                            + "它会被当成未知类型拒绝（B2-12 的最坏形态）",
                    ActionTypes.ALL.contains(value));
            assertTrue(ActionTypes.isKnown(value));
        }
        assertTrue("反射没读到任何常量，测试本身失效了", checked > 30);
    }

    @Test
    public void allHasNoDuplicatesAndIsSelfConsistent() {
        assertEquals("ALL 里有重复项", ActionTypes.ALL.size(),
                new java.util.LinkedHashSet<String>(ActionTypes.ALL).size());
        for (String type : ActionTypes.ALL) {
            assertNotNull(type);
            assertFalse("ALL 里不能有空串", type.isEmpty());
        }
        assertEquals(ActionTypes.ALL.size(), new java.util.LinkedHashSet<String>(ActionTypes.ALL).size());
    }

    @Test
    public void unknownTypesAreNotKnown() {
        assertFalse(ActionTypes.isKnown("no_such_action"));
        assertFalse(ActionTypes.isKnown("PROCEED"));   // 大小写敏感
        assertFalse(ActionTypes.isKnown(""));
        assertFalse(ActionTypes.isKnown(null));
    }

    @Test
    public void theTypesTheIssuesTalkAboutAreKnown() {
        for (String type : new String[] {
                ActionTypes.BACK, ActionTypes.PROCEED, ActionTypes.OPEN_CHEST,
                ActionTypes.CLAIM_RELIC, ActionTypes.CANCEL_HAND_SELECT,
                ActionTypes.CHOOSE_GRID_CARDS}) {
            assertTrue(type + " 必须是已知类型", ActionTypes.isKnown(type));
        }
    }
}
