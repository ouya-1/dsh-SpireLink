package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #95: a locked ("需要：金神像") event option was advertised and executed. On ForgottenAltar
 * that really swapped Burning Blood for Bloody Idol — an irreversible change to the run.
 *
 * <p>Both sides consult {@link EventOptionPolicy} (planner skips, executor refuses), so an
 * advertised option is always one the game would accept.</p>
 */
public class EventOptionPolicyTest {

    @Test
    public void aNormalOptionIsSelectable() {
        assertTrue(EventOptionPolicy.selectable(false, true));
    }

    @Test
    public void aLockedOptionIsNotSelectable() {
        assertFalse(EventOptionPolicy.selectable(true, true));
        assertFalse("锁定且没有 hitbox 也不能点", EventOptionPolicy.selectable(true, false));
    }

    @Test
    public void anOptionWithoutAHitboxIsNotSelectable() {
        assertFalse(EventOptionPolicy.selectable(false, false));
    }

    @Test
    public void theRejectionSaysWhyAndShowsTheOptionText() {
        String message = EventOptionPolicy.lockedMessage("[锁定] 需要：金神像");
        assertNotNull(message);
        assertTrue(message.contains("锁定"));
        assertTrue("要带上原选项文案，方便 agent 对照: " + message, message.contains("金神像"));
        assertFalse("没有文案时不能拼出空括号", EventOptionPolicy.lockedMessage(null).contains("："));
        assertFalse(EventOptionPolicy.lockedMessage("").contains("："));
    }
}
