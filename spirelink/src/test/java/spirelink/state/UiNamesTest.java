package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Coverage: every engine enum value must be mapped (I5: no silent regressions). */
public class UiNamesTest {

    private static final String[] SCREENS = {
            "NONE", "COMBAT_REWARD", "CARD_REWARD", "BOSS_REWARD", "GRID", "HAND_SELECT",
            "CHOOSE_ONE", "SHOP", "MAP", "FTUE", "NO_INTERACT", "TRANSFORM", "DEATH", "VICTORY",
            "UNLOCK", "DOOR_UNLOCK", "NEOW_UNLOCK", "MASTER_DECK_VIEW", "DISCARD_VIEW",
            "EXHAUST_VIEW", "GAME_DECK_VIEW", "SETTINGS", "INPUT_SETTINGS", "CREDITS",
    };

    private static final String[] MENUS = {
            "NONE", "MAIN_MENU", "CHAR_SELECT", "PANEL_MENU", "SETTINGS", "CARD_LIBRARY",
            "CARD_LIBRARY_SORTING", "RELIC_VIEW", "STATS", "LEADERBOARD", "SEED_OPTIONS",
            "SEED_SCREEN", "MOD_LIST", "MOD_LIST_CREDITS", "ABANDON_RUN", "PROFILE_SELECT",
            "PROFILE_POPUP", "INFO", "HELP", "LIST_VIEW", "SAVE_SLOT", "CREDITS",
    };

    @Test
    public void everyCurrentScreenIsKnown() {
        assertTrue(SCREENS.length == 24);
        for (String name : SCREENS) {
            assertTrue("未映射的 CurrentScreen: " + name, UiNames.knowsScreen(name));
        }
    }

    @Test
    public void everyMenuScreenIsKnown() {
        assertTrue(MENUS.length == 22);
        for (String name : MENUS) {
            assertTrue("未映射的 CurScreen: " + name, UiNames.knowsMenu(name));
        }
    }

    @Test
    public void menuNamesNeverNull() {
        for (String name : MENUS) {
            assertNotNull(UiNames.ofMenu(name));
        }
    }
}
