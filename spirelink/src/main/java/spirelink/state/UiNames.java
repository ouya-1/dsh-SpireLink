package spirelink.state;

import java.util.HashMap;
import java.util.Map;

/**
 * Screen name to {@code ui} mapping (design 7.2). Kept as pure string tables so the full enum
 * surface can be unit tested without a running game.
 */
public final class UiNames {

    private static final Map<String, String> SCREENS = new HashMap<String, String>();
    private static final Map<String, String> MENUS = new HashMap<String, String>();

    static {
        SCREENS.put("NONE", null);
        SCREENS.put("COMBAT_REWARD", "combat_reward");
        SCREENS.put("CARD_REWARD", "card_reward");
        SCREENS.put("BOSS_REWARD", "boss_reward");
        SCREENS.put("GRID", "grid");
        SCREENS.put("HAND_SELECT", "hand_select");
        SCREENS.put("CHOOSE_ONE", "choose_one");
        SCREENS.put("SHOP", "shop");
        SCREENS.put("MAP", "map");
        SCREENS.put("FTUE", "tutorial_tip");
        SCREENS.put("NO_INTERACT", "view");
        SCREENS.put("TRANSFORM", "transform");
        SCREENS.put("DEATH", "death");
        SCREENS.put("VICTORY", "victory");
        SCREENS.put("UNLOCK", "view");
        SCREENS.put("DOOR_UNLOCK", "door_unlock");
        SCREENS.put("NEOW_UNLOCK", "neow");
        SCREENS.put("MASTER_DECK_VIEW", "tutorial_tip");
        SCREENS.put("DISCARD_VIEW", "tutorial_tip");
        SCREENS.put("EXHAUST_VIEW", "tutorial_tip");
        SCREENS.put("GAME_DECK_VIEW", "tutorial_tip");
        SCREENS.put("SETTINGS", "view");
        SCREENS.put("INPUT_SETTINGS", "view");
        SCREENS.put("CREDITS", "credits");

        MENUS.put("NONE", "main_menu");
        MENUS.put("MAIN_MENU", "main_menu");
        MENUS.put("CHAR_SELECT", "char_select");
        MENUS.put("PANEL_MENU", "mode_select");
        MENUS.put("SETTINGS", "main_menu");
        MENUS.put("CARD_LIBRARY", "main_menu");
        MENUS.put("CARD_LIBRARY_SORTING", "main_menu");
        MENUS.put("RELIC_VIEW", "main_menu");
        MENUS.put("STATS", "main_menu");
        MENUS.put("LEADERBOARD", "main_menu");
        MENUS.put("SEED_OPTIONS", "main_menu");
        MENUS.put("SEED_SCREEN", "main_menu");
        MENUS.put("MOD_LIST", "main_menu");
        MENUS.put("MOD_LIST_CREDITS", "main_menu");
        MENUS.put("ABANDON_RUN", "main_menu");
        MENUS.put("PROFILE_SELECT", "main_menu");
        MENUS.put("PROFILE_POPUP", "main_menu");
        MENUS.put("INFO", "main_menu");
        MENUS.put("HELP", "main_menu");
        MENUS.put("LIST_VIEW", "main_menu");
        MENUS.put("SAVE_SLOT", "main_menu");
        MENUS.put("CREDITS", "main_menu");
    }

    private UiNames() {
    }

    /** @return the ui name, or {@code null} meaning "not a screen of its own" */
    public static String ofScreen(String screenName) {
        return SCREENS.get(screenName);
    }

    public static String ofMenu(String screenName) {
        String ui = MENUS.get(screenName);
        return ui == null ? "main_menu" : ui;
    }

    /** Chinese name for a map room type, used by action labels and by humans reading the HUD. */
    /**
     * Short machine code for a map node type ({@code monster} / {@code elite} / …).
     *
     * <p>Used instead of the Java class name in {@code map}: "MonsterRoomElite" costs 16 characters
     * per node for something the one-character {@code symbol} already says.
     */
    public static String roomCode(String roomClass) {
        if (roomClass == null || roomClass.isEmpty()) {
            return "";
        }
        switch (roomClass) {
            case "MonsterRoom":
                return "monster";
            case "MonsterRoomElite":
                return "elite";
            case "EventRoom":
                return "event";
            case "RestRoom":
                return "rest";
            case "ShopRoom":
                return "shop";
            case "TreasureRoom":
            case "TreasureRoomBoss":
                return "treasure";
            case "MonsterRoomBoss":
                return "boss";
            case "VictoryRoom":
                return "victory";
            default:
                return "other";
        }
    }

    public static String roomZh(String roomClass) {
        if (roomClass == null || roomClass.isEmpty()) {
            return "未知";
        }
        switch (roomClass) {
            case "MonsterRoom":
                return "怪物";
            case "MonsterRoomElite":
                return "精英";
            case "EventRoom":
                return "事件";
            case "RestRoom":
                return "休息";
            case "ShopRoom":
                return "商店";
            case "TreasureRoom":
            case "TreasureRoomBoss":
                return "宝箱";
            case "MonsterRoomBoss":
                return "Boss";
            case "VictoryRoom":
                return "胜利";
            case "NeowRoom":
                return "开局祝福";
            default:
                return roomClass;
        }
    }

    /** Human position on the map row: {@code 左2} / {@code 中} / {@code 右1}. */
    public static String columnZh(int x, int width) {
        int middle = Math.max(0, (width - 1) / 2);
        if (x == middle) {
            return "中";
        }
        return x < middle ? "左" + (middle - x) : "右" + (x - middle);
    }

    public static boolean knowsScreen(String name) {
        return SCREENS.containsKey(name);
    }

    public static boolean knowsMenu(String name) {
        return MENUS.containsKey(name);
    }
}
