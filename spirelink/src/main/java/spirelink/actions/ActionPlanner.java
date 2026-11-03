package spirelink.actions;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.CombatRewardScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@code legal_actions} for the current {@code ui} (design 8.1 + P1 opening chain).
 *
 * <p>Main thread only. Every entry maps to something that really exists on screen right now: no
 * invented {@code cancel} (review R8), map only offers reachable nodes, character list only offers
 * unlocked options.
 */
public final class ActionPlanner {

    private ActionPlanner() {
    }

    /** @return every action available in {@code ui} right now, ids {@code a1..aN}. */
    public static List<ActionSpec> actionsFor(String ui) {
        List<ActionSpec> out = new ArrayList<>();
        if (ui == null) {
            return out;
        }
        switch (ui) {
            case "main_menu":
                mainMenu(out);
                break;
            case "mode_select":
                modeSelect(out);
                break;
            case "char_select":
                charSelect(out);
                break;
            case "tutorial_tip":
                tutorialTip(out);
                break;
            case "neow":
                neow(out);
                break;
            case "map":
                map(out);
                break;
            case "combat":
                combat(out);
                break;
            case "hand_select":
                handSelect(out);
                break;
            case "combat_reward":
                combatReward(out);
                break;
            case "card_reward":
                cardReward(out);
                break;
            case "boss_reward":
                bossReward(out);
                break;
            case "rest":
                rest(out);
                break;
            case "shop":
            case "shop_room":
                shop(out);
                break;
            case "event":
                event(out);
                break;
            case "grid":
                grid(out);
                break;
            case "transform":
                // Issue #83: vanilla's "transform a card" picker *is* the GridCardSelectScreen
                // (forTransform = true); normally the screen is reported as GRID, occasionally as
                // TRANSFORM. Plan it as a card select either way, so the agent can actually finish the
                // transform instead of being handed a lone proceed that throws the choice away.
                // grid() always leaves at least one action behind (its own "never a trap" net), so this
                // can never turn transform into a dead end.
                grid(out);
                if (out.isEmpty() && canProceed()) {
                    add(out, ActionTypes.PROCEED, "退出当前画面", new JsonObject());
                }
                break;
            case "treasure":
                treasure(out);
                break;
            case "boss_cleared":
                // Issue #42: the act boss is dead, the map is not open yet and the only thing left is
                // to leave the room. The executor advances deterministically (advanceFromBoss /
                // openMapScreen), so the overlay button being hidden must not take the only action
                // away — same reasoning as the idle-with-COMPLETE room path below. Deliberately not
                // routed through the default branch's canProceed() fallback.
                add(out, ActionTypes.PROCEED, "打开地图", new JsonObject());
                break;
            case "death":
            case "victory":
                runOver(out);
                break;
            case "view":
                viewScreen(out);
                break;
            default:
                // I4 fallback, but only with an action the executor can actually perform:
                // offering a proceed the game will refuse is worse than offering nothing.
                boolean screenUp = AbstractDungeon.screen != null
                        && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE;
                if ("idle".equals(ui)) {
                    // A bare proceed during a room transition is what jumped the boss chest: idle may
                    // only be left when the current room is genuinely finished (phase COMPLETE).
                    AbstractRoom idleRoom = currentRoom();
                    boolean roomDone = idleRoom != null
                            && idleRoom.phase == AbstractRoom.RoomPhase.COMPLETE;
                    if (roomDone) {
                        // Executor opens the map directly, so the overlay button being hidden must not
                        // remove the only action (that left the agent with nothing to do).
                        add(out, ActionTypes.PROCEED, "打开地图", new JsonObject());
                    }
                } else if (canProceed() || screenUp) {
                    add(out, ActionTypes.PROCEED, "退出当前画面", new JsonObject());
                }
                break;
        }
        return out;
    }

    // ---------------------------------------------------------------- main menu

    private static void mainMenu(List<ActionSpec> out) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return;
        }
        if (abandonPopupShown(menu)) {
            // The confirm dialog owns the screen: only yes/no make sense right now.
            add(out, ActionTypes.CONFIRM_ABANDON, "确认放弃存档", new JsonObject());
            add(out, ActionTypes.CANCEL_ABANDON, "取消", new JsonObject());
            return;
        }
        if (menu.buttons == null) {
            return;
        }
        for (MenuButton button : menu.buttons) {
            if (button == null || button.result == null) {
                continue;
            }
            switch (button.result) {
                case PLAY:
                    add(out, ActionTypes.OPEN_MODE_SELECT, "打开模式选择", new JsonObject());
                    break;
                case RESUME_GAME:
                    // The button list is computed when the menu opens, so after "abandon run" the
                    // game can still show "Continue" for a save that is already gone. Trust the file.
                    if (saveFileExists()) {
                        add(out, ActionTypes.CONTINUE_RUN, "继续上次的一局", new JsonObject());
                    } else {
                        spirelink.util.Diag.warn("main_menu: 存档文件已不存在，不再给 continue_run");
                    }
                    break;
                case ABANDON_RUN:
                    add(out, ActionTypes.ABANDON_RUN, "放弃存档（回到新局）", new JsonObject());
                    break;
                default:
                    break;
            }
        }
    }

    /** True when the vanilla "abandon run?" confirmation is on screen. */
    public static boolean abandonPopupShown(MainMenuScreen menu) {
        return menu != null && menu.abandonPopup != null && menu.abandonPopup.shown;
    }

    // ---------------------------------------------------------------- mode select

    private static void modeSelect(List<ActionSpec> out) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        String[] modes = {"standard", "daily", "custom"};
        for (String mode : modes) {
            if (findModePanel(menu, mode) != null) {
                JsonObject payload = new JsonObject();
                payload.addProperty("mode", mode);
                add(out, ActionTypes.CHOOSE_MODE, "选择" + modeLabel(mode), payload);
            }
        }
        add(out, ActionTypes.BACK, "返回主菜单", new JsonObject());
    }

    static String modeLabel(String mode) {
        if ("standard".equals(mode)) {
            return "标准模式";
        }
        if ("daily".equals(mode)) {
            return "每日挑战";
        }
        return "自定模式";
    }

    /**
     * Finds the panel button for one mode.
     *
     * <p>Primary key is the button's real {@code result} ({@code PLAY_NORMAL} / {@code PLAY_DAILY}
     * / {@code PLAY_CUSTOM}) read via BaseMod's {@code ReflectionHacks} — the field is not public.
     * Color is only a fallback for builds where the field is missing.
     */
    static MainMenuPanelButton findModePanel(MainMenuScreen menu, String mode) {
        if (menu == null || menu.panelScreen == null || menu.panelScreen.panels == null) {
            return null;
        }
        String wanted = panelResultName(mode);
        StringBuilder probe = new StringBuilder();
        for (MainMenuPanelButton panel : menu.panelScreen.panels) {
            if (panel == null || panel.hb == null) {
                continue;
            }
            String result = resultOf(panel);
            probe.append(result == null ? "?" : result).append('/')
                    .append(panel.pColor == null ? "?" : panel.pColor.name()).append(' ');
            if (result != null && result.equals(wanted)) {
                spirelink.util.Diag.info("模式面板: " + wanted + " 命中，面板清单 " + probe);
                return panel;
            }
        }
        spirelink.util.Diag.info("模式面板: 找 " + wanted + " 未命中（result），清单 " + probe
                + "，改用颜色兜底");
        MainMenuPanelButton.PanelColor want;
        if ("daily".equals(mode)) {
            want = MainMenuPanelButton.PanelColor.BLUE;
        } else if ("custom".equals(mode)) {
            want = MainMenuPanelButton.PanelColor.BEIGE;
        } else {
            want = MainMenuPanelButton.PanelColor.RED;
        }
        for (MainMenuPanelButton panel : menu.panelScreen.panels) {
            if (panel != null && panel.pColor == want && panel.hb != null) {
                return panel;
            }
        }
        return null;
    }

    private static String panelResultName(String mode) {
        if ("daily".equals(mode)) {
            return "PLAY_DAILY";
        }
        if ("custom".equals(mode)) {
            return "PLAY_CUSTOM";
        }
        return "PLAY_NORMAL";
    }

    /** Reads {@code MainMenuPanelButton.result} (private) without guessing its name. */
    private static String resultOf(MainMenuPanelButton panel) {
        try {
            Object result = basemod.ReflectionHacks.getPrivate(panel, MainMenuPanelButton.class,
                    "result");
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- char select

    /**
     * One row of the character select screen, reduced to plain values.
     *
     * <p>Deliberately free of game types: the ordering rules below are where issue #49 lived, so
     * they must be testable without a running game.
     */
    static final class CharOption {
        final String id;
        final String name;
        final boolean selected;
        final boolean locked;

        CharOption(String id, String name, boolean selected, boolean locked) {
            this.id = id;
            this.name = name;
            this.selected = selected;
            this.locked = locked;
        }
    }

    private static void charSelect(List<ActionSpec> out) {
        CharacterSelectScreen screen = CardCrawlGame.mainMenuScreen == null
                ? null : CardCrawlGame.mainMenuScreen.charSelectScreen;
        if (screen == null || screen.options == null) {
            return;
        }
        List<CharOption> options = new ArrayList<CharOption>();
        for (CharacterOption option : screen.options) {
            if (option == null || option.c == null || option.hb == null || option.locked) {
                continue;
            }
            AbstractPlayer.PlayerClass clazz = option.c.chosenClass;
            if (clazz == null) {
                continue;
            }
            options.add(new CharOption(clazz.name(), option.name, option.selected, false));
        }
        // Ascension is only reachable through tiny arrows in vanilla; the pure planner advertises a
        // short ladder instead of 21 rows, so it needs the live cap and current level.
        charSelectActions(out, options, ascensionCap(screen),
                screen.isAscensionMode ? screen.ascensionLevel : 0);
    }

    /**
     * Action list for the character select screen (issue #49).
     *
     * <p>Two rules carry the fix:
     * <ul>
     *   <li>the already-selected character stays in the list ({@code 保持 X}, payload {@code
     *       selected:true}) — hiding it meant that "take the first legal action" switched to
     *       another character, which made the first one reappear: the endless A→B→A jumping;</li>
     *   <li>with a character selected, {@code embark} is {@code actions[0]}, so a naive harness
     *       that always takes the first action starts the run instead of re-choosing.</li>
     * </ul>
     *
     * <p>Ascension stays available, but after {@code embark}: it is an optional tweak, never the
     * default move. Without a selected character nothing changes — one {@code choose_character} per
     * unlocked character, then {@code back}.
     */
    static void charSelectActions(List<ActionSpec> out, List<CharOption> options, int cap,
            int currentAscension) {
        List<CharOption> rows = new ArrayList<CharOption>();
        CharOption selected = null;
        if (options != null) {
            for (CharOption option : options) {
                if (option == null || option.id == null || option.locked) {
                    continue;
                }
                rows.add(option);
                if (option.selected && selected == null) {
                    selected = option;
                }
            }
        }
        if (selected != null) {
            // First action = "start the run": what a human who already picked does next.
            add(out, ActionTypes.EMBARK, "启程", new JsonObject());
        }
        for (CharOption option : rows) {
            JsonObject payload = new JsonObject();
            payload.addProperty("character", option.id);
            if (option == selected) {
                // Re-choosing the active character is a no-op in ActionExecutor (it answers
                // {@code verified()} without touching the screen), so advertising it cannot toggle
                // anything: this row is the "keep what is already selected" answer. #122 · C13-05:
                // that answer must be verified-and-not-ok, otherwise "keeping" twice is an ok that
                // moves nothing -> queued -> ACTION_IN_FLIGHT for the next act.
                payload.addProperty("selected", true);
                add(out, ActionTypes.CHOOSE_CHARACTER, "保持 " + option.name, payload);
            } else {
                add(out, ActionTypes.CHOOSE_CHARACTER, "选择" + option.name, payload);
            }
        }
        if (selected != null) {
            // QA round 6: the ladder now lives in AscensionRequest.LADDER, which is also what the
            // executor validates against - the advertised set and the accepted set cannot drift.
            for (int level : AscensionRequest.LADDER) {
                if (level > cap || level == currentAscension) {
                    continue;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("level", level);
                add(out, ActionTypes.CHOOSE_ASCENSION,
                        level == 0 ? "关闭升天（普通难度）" : "升天 " + level, payload);
            }
        }
        add(out, ActionTypes.BACK, "返回主菜单", new JsonObject());
    }

    /** True when the chosen character really has an autosave on disk. */
    private static boolean saveFileExists() {
        try {
            if (CardCrawlGame.chosenCharacter == null) {
                return true;
            }
            String path = com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue
                    .getPlayerSavePath(CardCrawlGame.chosenCharacter);
            return com.badlogic.gdx.Gdx.files.local(path).exists();
        } catch (Throwable t) {
            return true; // unknown: keep the vanilla button rather than hiding a real save
        }
    }

    /** Highest ascension the currently selected character has unlocked (20 when unknown). */
    private static int ascensionCap(CharacterSelectScreen screen) {
        for (CharacterOption option : screen.options) {
            if (option != null && option.selected) {
                return ascensionCap(option);
            }
        }
        return 20;
    }

    /**
     * The character's unlocked ascension ceiling.
     *
     * <p>Reads the per-character pref directly ({@code ASCENSION_LEVEL}) instead of the option's
     * {@code maxAscensionLevel} field: that field is only refreshed inside {@code updateHitbox()},
     * which runs a frame after the character is selected, so a fresh {@code choose_character} would
     * otherwise report a stale ceiling of 1.</p>
     */
    public static int ascensionCap(CharacterOption option) {
        try {
            if (option != null && option.c != null && option.c.getPrefs() != null) {
                int cap = option.c.getPrefs().getInteger("ASCENSION_LEVEL", 1);
                return Math.max(0, Math.min(cap, 20));
            }
        } catch (Throwable ignored) {
            // fall through to the vanilla maximum
        }
        return 20;
    }

    // ---------------------------------------------------------------- tutorial tip

    private static void tutorialTip(List<ActionSpec> out) {
        // MultiPageFtue pages through the same "got it" button; each call advances one page.
        add(out, ActionTypes.DISMISS_TIP, "关闭教程提示", new JsonObject());
    }

    // ---------------------------------------------------------------- neow

    private static void neow(List<ActionSpec> out) {
        List<LargeDialogOptionButton> options = dialogOptions();
        if (options == null || options.isEmpty()) {
            // Post-death NEOW_UNLOCK screen (cards/relics just unlocked): there is nothing to pick,
            // the only way out is the proceed button. Returning nothing froze the agent for minutes.
            add(out, ActionTypes.PROCEED, "返回主菜单", new JsonObject());
            return;
        }
        int offered = 0;
        for (int i = 0; i < options.size(); i++) {
            LargeDialogOptionButton option = options.get(i);
            if (option == null || option.isDisabled) {
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("index", i);
            add(out, ActionTypes.CHOOSE_NEOW_REWARD, "选项：" + clean(option.msg), payload);
            offered++;
        }
        if (offered == 1) {
            add(out, ActionTypes.PROCEED, "继续", new JsonObject());
        }
    }

    /**
     * Clickable event options.
     *
     * <p>Two dialog flavours exist in this build: {@code RoomEventDialog} (Neow and most events;
     * its {@code optionList} is static and it reports the pick through
     * {@code RoomEventDialog.selectedOption}) and the older per-event
     * {@code GenericEventDialog} instance in {@code imageEventText}. Whichever currently holds
     * options wins, so this works for both.
     */
    public static List<LargeDialogOptionButton> dialogOptions() {
        AbstractRoom room = currentRoom();
        if (room == null || room.event == null) {
            return null;
        }
        // Two possible homes for the option buttons: the static RoomEventDialog list (used by some
        // events) and the per-event imageEventText list. Read both and merge, so an event whose
        // options only live in one of them is not invisible.
        java.util.LinkedHashMap<Integer, LargeDialogOptionButton> merged =
                new java.util.LinkedHashMap<Integer, LargeDialogOptionButton>();
        Object staticList = spirelink.util.FieldKit.staticFieldOrNull(
                RoomEventDialog.class, "optionList");
        if (staticList instanceof java.util.List) {
            int index = 0;
            for (Object option : (java.util.List<?>) staticList) {
                if (option instanceof LargeDialogOptionButton) {
                    merged.put(Integer.valueOf(index++), (LargeDialogOptionButton) option);
                }
            }
        }
        if (room.event.imageEventText != null && room.event.imageEventText.optionList != null) {
            for (LargeDialogOptionButton option : room.event.imageEventText.optionList) {
                if (option == null) {
                    continue;
                }
                boolean duplicate = false;
                for (LargeDialogOptionButton existing : merged.values()) {
                    if (existing == option) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    merged.put(Integer.valueOf(merged.size()), option);
                }
            }
        }
        if (!merged.isEmpty()) {
            return new java.util.ArrayList<LargeDialogOptionButton>(merged.values());
        }
        if (room.event.roomEventText != null && RoomEventDialog.optionList != null
                && !RoomEventDialog.optionList.isEmpty()) {
            return RoomEventDialog.optionList;
        }
        if (room.event.imageEventText != null && room.event.imageEventText.optionList != null
                && !room.event.imageEventText.optionList.isEmpty()) {
            return room.event.imageEventText.optionList;
        }
        return RoomEventDialog.optionList != null
                ? RoomEventDialog.optionList
                : (room.event.imageEventText == null ? null : room.event.imageEventText.optionList);
    }

    /** True while a won battle has not yet been replaced by the reward screen. */
    public static boolean battleOver() {
        AbstractRoom room = currentRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            return false;
        }
        try {
            MonsterGroup group = AbstractDungeon.getMonsters();
            return group != null && group.areMonstersBasicallyDead();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * No longer used as a gate. "No option yet" is a perfectly operable state now that the planner
     * offers {@code event_continue}, so blocking readiness here starved the agent (DeadAdventurer
     * stayed NOT_READY for 48 s while an action was actually available).
     */
    public static boolean eventTextTyping() {
        return false;
    }

    /** Rate-limited event diagnosis, called from the state gate when it refuses readiness. */
    public static void logEventWait() {
        try {
            AbstractRoom room = currentRoom();
            if (room == null || room.event == null || room.phase != AbstractRoom.RoomPhase.EVENT) {
                return;
            }
            List<LargeDialogOptionButton> options = dialogOptions();
            eventDiag(options == null ? 0 : options.size());
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /**
     * {@code AbstractDungeon.getCurrRoom()} dereferences {@code currMapNode} without a null check
     * and throws while the dungeon is still being built (main menu, run start), so every planner
     * lookup goes through here.
     */
    private static AbstractRoom currentRoom() {
        try {
            return AbstractDungeon.getCurrMapNode() == null ? null : AbstractDungeon.getCurrRoom();
        } catch (Throwable t) {
            return null;
        }
    }


    // ---------------------------------------------------------------- combat

    /**
     * Combat actions. Only offered while the game waits for the player
     * ({@code GameActionManager.Phase.WAITING_ON_USER}); during animations the list is empty and the
     * harness is expected to poll instead of queueing, so {@code act} never fights the action queue.
     */
    private static void combat(List<ActionSpec> out) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || !playerTurnReady()) {
            return;
        }
        List<AbstractMonster> monsters = aliveMonsters();
        if (player.hand != null && player.hand.group != null) {
            for (AbstractCard card : player.hand.group) {
                if (card == null) {
                    continue;
                }
                boolean needsTarget = card.target == AbstractCard.CardTarget.ENEMY
                        || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
                if (needsTarget) {
                    for (int i = 0; i < monsters.size(); i++) {
                        AbstractMonster monster = monsters.get(i);
                        if (!card.canUse(player, monster)) {
                            continue;
                        }
                        JsonObject payload = new JsonObject();
                        payload.addProperty("uuid", card.uuid.toString());
                        payload.addProperty("target_index", monsterIndex(monster, i));
                        add(out, ActionTypes.PLAY_CARD, "出牌 " + card.name + " → "
                                + monsterLabel(monster, monsterIndex(monster, i)), payload);
                    }
                } else if (card.canUse(player, null)) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("uuid", card.uuid.toString());
                    add(out, ActionTypes.PLAY_CARD, "出牌 " + card.name, payload);
                }
            }
        }
        boolean myTurn = playerTurnNow();
        if (player.potions != null) {
            for (int slot = 0; slot < player.potions.size(); slot++) {
                AbstractPotion potion = player.potions.get(slot);
                if (spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                    continue;
                }
                // The executor refuses use_potion outside the player's turn, so the planner must not
                // offer it there either (I4: an offered action may never be rejected).
                if (myTurn && potion.canUse()) {
                    if (potion.targetRequired) {
                        for (int i = 0; i < monsters.size(); i++) {
                            AbstractMonster monster = monsters.get(i);
                            JsonObject payload = new JsonObject();
                            payload.addProperty("slot", slot);
                            payload.addProperty("target_index", monsterIndex(monster, i));
                            add(out, ActionTypes.USE_POTION, "用药水 " + potion.name + " → "
                                    + monsterLabel(monster, monsterIndex(monster, i)), payload);
                        }
                    } else {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("slot", slot);
                        add(out, ActionTypes.USE_POTION, "用药水 " + potion.name, payload);
                    }
                }
                if (potion.canDiscard()) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("slot", slot);
                    add(out, ActionTypes.DISCARD_POTION, "丢弃药水 " + potion.name, payload);
                }
            }
        }
        if (endTurnAvailable()) {
            add(out, ActionTypes.END_TURN, "结束回合", new JsonObject());
        }
        // #122 · C13-03: the executor accepts abandon_mid_run on every in-run screen (it mirrors the
        // pause menu's Abandon Run and re-checks its own preconditions), but the planner used to list
        // it on the map only - so on the combat screen it was "executable but never advertised", i.e.
        // legal_actions was not authoritative (QA round 13 · C13-03). Combat is the screen where a run
        // can be lost for good (unwinnable fight, wedged animation), so the escape hatch is advertised
        // here as well. It stays LAST: a "take the first action" harness must never walk into it, and
        // the label says what it costs, because the save is deleted (unlike the map's shorter label).
        add(out, ActionTypes.ABANDON_MID_RUN, "放弃本局（删存档并回主菜单）", new JsonObject());
    }

    /**
     * True only when the vanilla end-turn button is enabled — that flag already encodes "player's
     * turn, no animation running". Ending the turn on our own twice in a row used to bump
     * {@code GameActionManager.turn} without a real turn passing.
     */
    public static boolean endTurnAvailable() {
        try {
            return AbstractDungeon.overlayMenu != null
                    && AbstractDungeon.overlayMenu.endTurnButton != null
                    && AbstractDungeon.overlayMenu.endTurnButton.enabled
                    && AbstractDungeon.player != null
                    && !AbstractDungeon.player.endTurnQueued
                    && currentRoom() != null
                    && currentRoom().phase == AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean playerTurnReady() {
        GameActionManager manager = AbstractDungeon.actionManager;
        if (manager == null) {
            return false;
        }
        try {
            return manager.phase == GameActionManager.Phase.WAITING_ON_USER;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Live monsters, in the raw order {@code get_state.combat.monsters[].index} uses. */
    private static List<AbstractMonster> aliveMonsters() {
        List<AbstractMonster> out = new ArrayList<>();
        MonsterGroup group = AbstractDungeon.getMonsters();
        if (group == null || group.monsters == null) {
            return out;
        }
        for (AbstractMonster monster : group.monsters) {
            if (monster == null || monster.isDeadOrEscaped() || monster.currentHealth <= 0) {
                continue;
            }
            out.add(monster);
        }
        return out;
    }

    /**
     * Same number as {@code get_state.combat.monsters[].index} (slot in the room's monster list),
     * so the agent can copy it straight from the state into {@code target_index}.
     */
    public static int monsterIndex(AbstractMonster monster, int aliveOrdinal) {
        MonsterGroup group = AbstractDungeon.getMonsters();
        if (group != null && group.monsters != null) {
            int raw = group.monsters.indexOf(monster);
            if (raw >= 0) {
                return raw;
            }
        }
        return aliveOrdinal;
    }

    private static String monsterLabel(AbstractMonster monster, int index) {
        return "第" + (index + 1) + "只(" + monster.name + ")";
    }

    /**
     * Every selectable target as {@code "0=第1只(酸液史莱姆), 2=第2只(尖刺怪)"} (issue #104).
     *
     * <p>The numbers are the same {@code target_index} values {@code get_state.combat.monsters[]} and
     * the advertised {@code play_card}/{@code use_potion} payloads use, so a TARGET_REQUIRED rejection
     * lists targets the caller can copy straight back.</p>
     */
    public static String targetChoices() {
        StringBuilder sb = new StringBuilder();
        List<AbstractMonster> alive = aliveMonsters();
        for (int i = 0; i < alive.size(); i++) {
            AbstractMonster monster = alive.get(i);
            int index = monsterIndex(monster, i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(index).append('=').append(monsterLabel(monster, index));
        }
        return sb.length() == 0 ? "（现在没有可选目标）" : sb.toString();
    }

    // ---------------------------------------------------------------- rewards

    /** Post-combat rewards: one {@code take_reward} per unclaimed item, then {@code proceed}. */
    /** True when every potion slot holds a real potion. */
    public static boolean potionSlotsFull() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null || player.potions == null || player.potions.isEmpty()) {
                return false;
            }
            for (AbstractPotion potion : player.potions) {
                if (spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void combatReward(List<ActionSpec> out) {
        CombatRewardScreen screen = AbstractDungeon.combatRewardScreen;
        if (screen == null || screen.rewards == null) {
            return;
        }
        boolean allDone = true;
        boolean skippedPotion = false;
        for (int i = 0; i < screen.rewards.size(); i++) {
            RewardItem reward = screen.rewards.get(i);
            // ignoreReward is the third "cannot be taken" flag next to isDone (it is what the mod
            // itself sets on a relicLink reward it hands back to the game); advertising it would be
            // advertising a rejection, and take_reward now answers exactly that (#101).
            if (reward == null || reward.isDone || reward.ignoreReward) {
                continue;
            }
            if (reward.type == RewardItem.RewardType.POTION && potionSlotsFull()) {
                // Never offer an action the executor must reject (invariant I4): a full belt simply
                // cannot take this potion, so the screen shows only the way out.
                skippedPotion = true;
                continue;
            }
            allDone = false;
            JsonObject payload = new JsonObject();
            payload.addProperty("index", i);
            String label = "领取奖励 " + clean(reward.text);
            // 0.1.6: 宝箱里的蓝钥匙和遗物是二选一（RewardItem.renderRelicLink 画的那条锁链），
            // 拿一个另一个就没了。把这件事写在动作名里，别让 agent 以为可以都拿。
            RewardItem linked = reward.relicLink;
            if (linked != null && !linked.isDone && !linked.ignoreReward) {
                label += "（与「" + clean(linked.text) + "」互斥，只能选一个）";
            }
            add(out, ActionTypes.TAKE_REWARD, label, payload);
        }
        if (skippedPotion) {
            // #116a: a skipped potion reward is only half the story. Both the state's blocked_reason
            // and the POTION_SLOT_FULL rejection say "先丢掉一瓶才能拿", which is a dead end unless this
            // screen also offers the discard - it did not, discard_potion was not a legal action on
            // combat_reward at all. Same predicate the executor checks (potion.canDiscard), so every
            // advertised discard is executable (I4).
            int before = out.size();
            discardPotionOptions(out);
            spirelink.util.Diag.info("combat_reward: 药水槽已满，跳过药水奖励（共 "
                    + screen.rewards.size() + " 条），改为广告 "
                    + (out.size() - before) + " 个 discard_potion");
        }
        // I5: the reward screen always keeps a way out. Some rewards cannot be taken at all (a potion
        // with a full belt), and stalling here is unrecoverable.
        // #114: after an act boss the exit is not "back to the map" - it is the Boss 遗物房, so the
        // label says so (BossRewardPolicy owns the wording, the executor runs the same transition).
        AbstractRoom rewardRoom = currentRoom();
        add(out, ActionTypes.PROCEED,
                BossRewardPolicy.rewardExitLabel(
                        rewardRoom == null ? null : rewardRoom.getClass().getSimpleName(),
                        AbstractDungeon.id, allDone || screen.hasTakenAll),
                new JsonObject());
    }

    /**
     * One {@code discard_potion} per potion the belt may drop (#116a).
     *
     * <p>Only advertised where a full belt is what blocks a reward, and only for slots whose potion
     * reports {@code canDiscard()} — the very check {@code ActionExecutor#potion} runs, so "advertised"
     * and "executable" stay one set. {@code use_potion} is deliberately absent: it needs the player's
     * turn, which a reward screen is not.</p>
     */
    private static void discardPotionOptions(List<ActionSpec> out) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || player.potions == null) {
            return;
        }
        for (int slot = 0; slot < player.potions.size(); slot++) {
            AbstractPotion potion = player.potions.get(slot);
            if (spirelink.state.PotionSerializer.isPlaceholder(potion) || !potion.canDiscard()) {
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("slot", slot);
            add(out, ActionTypes.DISCARD_POTION, "丢弃药水 " + potion.name, payload);
        }
    }


    /** Treasure room: the chest must be opened before the proceed button exists. */
    private static void treasure(List<ActionSpec> out) {
        AbstractRoom room = currentRoom();
        // The act-1/2 boss room (TreasureRoomBoss) is NOT a TreasureRoom: it holds a BossChest whose
        // open() is what brings up the boss-relic pick. Skipping it is how the relic got lost.
        if (room instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss) {
            // Same predicate the executor's proceed guard uses: a nil-chest room is repaired here so
            // the only action offered is always one that can actually run.
            BossChestGate.ensureChest();
            boolean bossRewardUp = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD;
            if (BossChestGate.mustOpenChest() && !bossRewardUp) {
                add(out, ActionTypes.OPEN_CHEST, "打开 Boss 宝箱", new JsonObject());
                spirelink.util.Diag.info("treasure: " + BossChestGate.describe() + " → [open_chest]");
                return;
            }
            if (!AbstractDungeon.isScreenUp) {
                // Executor can advance deterministically (goToNextDungeon), so this must be offered
                // even when the overlay button is still hidden by the reward pop-out animation.
                add(out, ActionTypes.PROCEED, "继续（离开本幕）", new JsonObject());
                spirelink.util.Diag.info("treasure: " + BossChestGate.describe() + " → [proceed]"
                        + " canProceed=" + canProceed());
            }
            return;
        }
        if (!(room instanceof com.megacrit.cardcrawl.rooms.TreasureRoom)) {
            return;
        }
        com.megacrit.cardcrawl.rewards.chests.AbstractChest chest =
                ((com.megacrit.cardcrawl.rooms.TreasureRoom) room).chest;
        if (chest == null) {
            return;
        }
        // Only while the chest is untouched: after open() the reward screen is up (isScreenUp) and the
        // room already holds rewards; offering open_chest again grew the reward list by a batch each time.
        boolean rewardsPending = room.rewards != null && !room.rewards.isEmpty();
        if (!chest.isOpen && !AbstractDungeon.isScreenUp && !rewardsPending) {
            add(out, ActionTypes.OPEN_CHEST, "打开宝箱", new JsonObject());
            return;
        }
        if (canProceed()) {
            add(out, ActionTypes.PROCEED, "继续", new JsonObject());
        }
    }

    /** True only when the vanilla proceed button is actually usable right now. */
    public static boolean canProceed() {
        try {
            if (AbstractDungeon.overlayMenu == null
                    || AbstractDungeon.overlayMenu.proceedButton == null) {
                return false;
            }
            Boolean hidden = basemod.ReflectionHacks.getPrivate(
                    AbstractDungeon.overlayMenu.proceedButton,
                    com.megacrit.cardcrawl.ui.buttons.ProceedButton.class, "isHidden");
            return hidden != null && !hidden.booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- screens (P2.5)

    private static void cardReward(List<ActionSpec> out) {
        CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
        if (screen == null || screen.rewardGroup == null) {
            return;
        }
        for (int i = 0; i < screen.rewardGroup.size(); i++) {
            AbstractCard card = screen.rewardGroup.get(i);
            if (card == null) {
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("index", i);
            payload.addProperty("uuid", String.valueOf(card.uuid));
            add(out, ActionTypes.TAKE_CARD, "拿牌 " + clean(card.name), payload);
        }
        // C7-01: skip_card is advertised from the very predicate the executor runs it with, so
        // "advertised" and "executable" are one set. Vanilla hides the skip button on the flavours
        // that cannot be skipped (draftOpen / chooseOneOpen / discoveryOpen); the old code advertised
        // skip everywhere and then had to fake a close. CardRewardPolicy.skipAvailable fails open when
        // the button cannot be read, so an odd state can never cost the screen its only exit.
        boolean skipOffered = CardRewardPolicy.skipAvailable(
                screen.rewardGroup != null, skipButtonVisible());
        planInfo("card_reward.skip", skipOffered ? null
                : "card_reward: 这个选牌界面原版没有跳过按钮（skipButton 隐藏），只广告 take_card");
        if (skipOffered) {
            add(out, ActionTypes.SKIP_CARD, "跳过这次选牌", new JsonObject());
        }
    }

    /**
     * {@code SkipCardButton.isHidden} — the button a human presses to skip a card reward (C7-01).
     *
     * <p>This is the same object the native skip path lives on: {@code SkipCardButton.update()} calls
     * {@code AbstractDungeon.closeCurrentScreen()}. Shared with {@code ActionExecutor#skipCard()} so
     * both sides ask one question.</p>
     *
     * @return {@code null} when the button cannot be read at all, so callers can fail open
     */
    static Boolean skipButtonVisible() {
        try {
            Object skip = basemod.ReflectionHacks.getPrivate(AbstractDungeon.cardRewardScreen,
                    CardRewardScreen.class, "skipButton");
            if (skip == null) {
                return null;
            }
            Object hidden = spirelink.util.FieldKit.fieldOrNull(skip, "isHidden");
            return hidden instanceof Boolean
                    ? Boolean.valueOf(!((Boolean) hidden).booleanValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void bossReward(List<ActionSpec> out) {
        Object screen = AbstractDungeon.bossRelicScreen;
        if (screen == null) {
            return;
        }
        try {
            Object relics = basemod.ReflectionHacks.getPrivate(screen, screen.getClass(), "relics");
            if (!(relics instanceof java.util.List)) {
                return;
            }
            java.util.List<?> list = (java.util.List<?>) relics;
            for (int i = 0; i < list.size(); i++) {
                Object relic = list.get(i);
                if (relic == null) {
                    continue;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("index", i);
                String name = relic instanceof com.megacrit.cardcrawl.relics.AbstractRelic
                        ? ((com.megacrit.cardcrawl.relics.AbstractRelic) relic).name : "";
                add(out, ActionTypes.CLAIM_RELIC, "收下遗物《" + name + "》", payload);
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("boss_reward: 读不到遗物列表: " + t);
        }
        // Boss relic screen always exposes a cancel ("skip") button; never leave it as a one-way door.
        add(out, ActionTypes.SKIP_RELIC, "跳过遗物", new JsonObject());
    }

    /** Campfire: the options are real objects with a public {@code useOption()}. */
    private static void rest(List<ActionSpec> out) {
        AbstractRoom room = currentRoom();
        if (room == null) {
            return;
        }
        try {
            // campfireUI is declared on RestRoom, not AbstractRoom: reading it through the
            // superclass threw NoSuchFieldException straight into the render loop.
            if (!(room instanceof com.megacrit.cardcrawl.rooms.RestRoom)) {
                return;
            }
            Object ui = ((com.megacrit.cardcrawl.rooms.RestRoom) room).campfireUI;
            if (ui == null) {
                // Campfire closed (option used): the room is done, so the only thing left is the
                // proceed button. Returning here used to leave the agent with zero actions (I4).
                if (proceedVisible()) {
                    add(out, ActionTypes.PROCEED, "继续", new JsonObject());
                }
                restDiag(-1, -1, true);
                return;
            }
            // Decisive condition: once the campfire has a selection it accepts no more choices, and
            // only the proceed button is meaningful from then on.
            Object somethingSelected = spirelink.util.FieldKit.fieldOrNull(ui, "somethingSelected");
            if (Boolean.TRUE.equals(somethingSelected)) {
                // I4: a campfire with a selection still has to hand out its exit action, otherwise
                // ready=false and the agent waits forever on a finished screen.
                add(out, ActionTypes.PROCEED, "继续", new JsonObject());
                restDiag(-1, -1, true);
                return;
            }
            int hiddenCount = 0;
            int optionCount = 0;
            Object buttons = spirelink.util.FieldKit.fieldOrNull(ui, "buttons");
            if (buttons instanceof java.util.List) {
                optionCount = ((java.util.List<?>) buttons).size();
                for (Object option : (java.util.List<?>) buttons) {
                    if (option == null || campfireAction(option.getClass().getSimpleName()) == null) {
                        continue;
                    }
                    // AbstractCampfireOption has no isHidden at all; the real "can still be used"
                    // flag is the public 'usable' boolean. Reading isHidden made used options look
                    // available forever, so the agent fired useOption() repeatedly and froze the game.
                    if (!campfireUsable(option)) {
                        hiddenCount++;
                        continue;
                    }
                    String type = campfireAction(option.getClass().getSimpleName());
                    add(out, type, campfireLabel(type), new JsonObject());
                }
            }
            restDiag(optionCount, hiddenCount, false);
            // Used options stay in the list, so "no options left" is not a reliable signal: offer
            // proceed whenever its button is actually visible.
            if (proceedVisible()) {
                add(out, ActionTypes.PROCEED, "继续", new JsonObject());
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("rest: 读不到篝火选项: " + t);
        }
    }

    /** Campfire options expose 'usable'; unreadable means "not usable" (never offer a spent option). */
    static boolean campfireUsable(Object option) {
        Object usable = spirelink.util.FieldKit.fieldOrNull(option, "usable");
        if (usable instanceof Boolean) {
            return ((Boolean) usable).booleanValue();
        }
        // Unknown shape: treat as usable so the executor's own check decides, but say it once.
        spirelink.util.FieldKit.noteMissing(option, "usable");
        return true;
    }

    /** True when the vanilla proceed button exists and is not hidden. */
    private static boolean proceedVisible() {
        try {
            if (AbstractDungeon.overlayMenu == null
                    || AbstractDungeon.overlayMenu.proceedButton == null) {
                return false;
            }
            Object hidden = spirelink.util.FieldKit.fieldOrNull(
                    AbstractDungeon.overlayMenu.proceedButton, "isHidden");
            return !(hidden instanceof Boolean) || !((Boolean) hidden).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile long lastRestDiagAt;

    /** Rate-limited diagnosis: says whether used options are actually hidden. */
    private static void restDiag(int buttons, int hidden, boolean campfireNull) {
        long now = System.currentTimeMillis();
        if (now - lastRestDiagAt < 1000L) {
            return;
        }
        lastRestDiagAt = now;
        try {
            spirelink.util.Diag.info("rest: buttons=" + buttons + " hidden=" + hidden
                    + " campfire=" + (campfireNull ? "null" : "ok")
                    + " proceedHidden=" + !proceedVisible()
                    + " phase=" + (currentRoom() == null ? "null" : currentRoom().phase));
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static String campfireAction(String simpleName) {
        switch (simpleName) {
            case "RestOption":
                return ActionTypes.REST;
            case "SmithOption":
                return ActionTypes.SMITH;
            case "TokeOption":
                return ActionTypes.TOKE;
            case "LiftOption":
                return ActionTypes.LIFT;
            default:
                return null;
        }
    }

    private static String campfireLabel(String type) {
        switch (type) {
            case ActionTypes.REST:
                return "休息";
            case ActionTypes.SMITH:
                return "锻造（升级一张牌）";
            case ActionTypes.TOKE:
                return "移除一张牌";
            case ActionTypes.LIFT:
                return "举铁（+力量）";
            default:
                return type;
        }
    }

    private static void shop(List<ActionSpec> out) {
        ShopScreen screen = AbstractDungeon.shopScreen;
        // Vanilla opens the shop only when the player clicks the merchant; without this the agent sat
        // in ShopRoom with "leave" as its only action for the whole run.
        // isActive alone is enough: after a purge the screen drops to NONE while the merchant stays
        // active, and requiring screen==SHOP forced a second open_shop for nothing.
        // isActive 不等于「货架看得见」：站在商店房里没点商人时 isActive 也是 true，
        // 此时给出 buy 会让 agent 买一个屏幕上不存在的东西（买了等于没买）。
        boolean onScreen = screen != null && screen.isActive && merchantVisible();
        if (!onScreen) {
            if (currentRoom() instanceof com.megacrit.cardcrawl.rooms.ShopRoom) {
                add(out, ActionTypes.OPEN_SHOP, "逛商店", new JsonObject());
            }
            add(out, ActionTypes.LEAVE, "离开商店", new JsonObject());
            return;
        }
        AbstractPlayer player = AbstractDungeon.player;
        int gold = player == null ? 0 : player.gold;
        // Issue #103: the belt is part of the advertisement condition, exactly like the combat-reward
        // potion item. With every slot full the shop still offered 买药水, and clicking it could only
        // ever answer POTION_SLOT_FULL - an advertised action the executor is guaranteed to reject.
        // Read once, before the shelf is walked, so every potion row sees the same value.
        boolean beltFull = potionSlotsFull();
        java.util.List<AbstractCard> cards = shopCards(screen);
        for (int i = 0; i < cards.size(); i++) {
            AbstractCard card = cards.get(i);
            if (card == null || card.price <= 0 || gold < card.price) {
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", "card");
            payload.addProperty("index", i);
            add(out, ActionTypes.BUY, "买卡 " + clean(card.name) + "（" + card.price + " 金）",
                    payload);
        }
        java.util.List<?> relics = spirelink.actions.ShopAccess.relics(screen);
        for (int i = 0; i < relics.size(); i++) {
            com.megacrit.cardcrawl.shop.StoreRelic store =
                    (com.megacrit.cardcrawl.shop.StoreRelic) relics.get(i);
            if (store == null || store.isPurchased || store.relic == null
                    || gold < store.price) {
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", "relic");
            payload.addProperty("index", i);
            add(out, ActionTypes.BUY, "买遗物 " + clean(store.relic.name) + "（" + store.price
                    + " 金）", payload);
        }
        java.util.List<?> potions = spirelink.actions.ShopAccess.potions(screen);
        int beltFullSkips = 0;
        for (int i = 0; i < potions.size(); i++) {
            com.megacrit.cardcrawl.shop.StorePotion store =
                    (com.megacrit.cardcrawl.shop.StorePotion) potions.get(i);
            if (store == null || store.isPurchased || store.potion == null
                    || gold < store.price) {
                continue;
            }
            if (beltFull) {
                beltFullSkips++;
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", "potion");
            payload.addProperty("index", i);
            add(out, ActionTypes.BUY, "买药水 " + clean(store.potion.name) + "（" + store.price
                    + " 金）", payload);
        }
        // C7-07: one line per shop state, not one per plan() call (the shelf used to report the same
        // sentence once for every potion it had to skip).
        planInfo("shop.beltFull", beltFullSkips > 0
                ? "shop: 药水槽已满，不广告 " + beltFullSkips + " 瓶买得起的药水（先喝掉/丢掉一瓶）"
                : null);
        if (screen.purgeAvailable && gold >= ShopScreen.actualPurgeCost) {
            add(out, ActionTypes.PURGE_CARD, "净化一张牌（" + ShopScreen.actualPurgeCost + " 金）",
                    new JsonObject());
        }
        add(out, ActionTypes.LEAVE, "离开商店", new JsonObject());
    }

    /**
     * Issue #115: is the merchant really the screen the agent is deciding on?
     *
     * <p>A read-only peek ({@code get_state{include:["deck"]}}) puts a view screen
     * ({@code MASTER_DECK_VIEW}) on top while {@code ShopScreen.isActive} stays true and
     * {@code buy} / {@code purge_card} / {@code leave} keep working — the executor only ever asks
     * {@code screen.isActive} for those, and it closes the peek before its legality gate
     * ({@code ActionExecutor#execute}). So the {@code screen == SHOP} test alone degraded
     * {@code legal_actions} to shop_room's {@code [open_shop, leave]} for the 4 s the human sees the
     * deck: {@code open_shop} was then rejected with 「商店已经打开了」 while {@code buy} really
     * executed. This is the same rule {@code StateBuilder#deriveUi} already applies (it reports the ui
     * underneath a peek), so the advertisement and the executor agree again.</p>
     */
    private static boolean merchantVisible() {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
            return true;
        }
        return spirelink.ui.DeckPeek.active()
                && "shop".equals(spirelink.ui.DeckPeek.underlyingUi());
    }

    /** Colored + colorless shop cards, in the order the merchant shows them. */
    public static java.util.List<AbstractCard> shopCards(ShopScreen screen) {
        java.util.List<AbstractCard> out = new ArrayList<>();
        try {
            if (screen.coloredCards != null) {
                out.addAll(screen.coloredCards);
            }
            if (screen.colorlessCards != null) {
                out.addAll(screen.colorlessCards);
            }
        } catch (Throwable ignored) {
            // empty list is fine
        }
        return out;
    }

    private static void event(List<ActionSpec> out) {
        if (spirelink.events.MatchGame.active() && spirelink.events.MatchGame.playable()) {
            // Only the PLAY screen accepts card flips. INTRO / RULE_EXPLANATION are ordinary dialog
            // steps: offering flips there produced 200 steps of no-op clicks ("翻开 #0 深呼吸" forever).
            matchGame(out);
            return;
        }
        AbstractRoom room = currentRoom();
        boolean eventGone = room == null || room.event == null
                || room.phase == AbstractRoom.RoomPhase.COMPLETE;
        List<LargeDialogOptionButton> options = dialogOptions();
        int count = options == null ? 0 : options.size();
        if (eventGone) {
            // Event finished (or never mounted): the room is still entered, so the only real action is
            // leaving it. Stale options must not be re-clicked - Lab's buttonEffect() calls openMap()
            // again on every click once it is COMPLETE, which is how the game ended up in its crash
            // screen with a null action in the queue.
            // Always proceed: the executor shows the overlay button before clicking it, so this cannot
            // be refused (event_continue would be, since there is no dialog left).
            add(out, ActionTypes.PROCEED, "离开事件房", new JsonObject());
            return;
        }
        if (count == 0) {
            if (eventDialogAdvanceable()) {
                // Text is still paging: clicking the dialog advances it.
                eventDiag(count);
                add(out, ActionTypes.EVENT_CONTINUE, "推进对话", new JsonObject());
            } else {
                // Nothing to advance (no dialog text and no options): the only honest action is exit.
                add(out, ActionTypes.PROCEED, "离开事件房", new JsonObject());
            }
            return;
        }
        int clickable = 0;
        for (int i = 0; i < options.size(); i++) {
            LargeDialogOptionButton option = options.get(i);
            if (option == null) {
                continue;
            }
            // Issue #95: a locked option must never be advertised. ForgottenAltar's
            // "[锁定] 需要：金神像" really executed when clicked: it swapped Burning Blood for Bloody
            // Idol (an irreversible change to the run) and a second click even handed out a Circlet.
            if (!EventOptionPolicy.selectable(option.isDisabled, option.hb != null)) {
                spirelink.util.Diag.info("event: 跳过不可点选项 #" + i + "（disabled="
                        + option.isDisabled + " hb=" + (option.hb != null) + "）"
                        + clean(option.msg));
                continue;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("index", i);
            add(out, ActionTypes.CHOOSE_OPTION, clean(option.msg), payload);
            clickable++;
        }
        if (clickable == 0) {
            // Every option is locked: hand the agent the only honest way out instead of an empty
            // action list (I4). Leaving costs the event outcome, clicking a locked option would break
            // the run - that trade is not close.
            add(out, ActionTypes.PROCEED, "离开事件房（选项都被锁定）", new JsonObject());
            spirelink.util.Diag.warn("event: " + count + " 个选项全部不可点，给 proceed 兜底出口");
        }
        // No proceed here: while dialog options exist the only meaningful action is clicking one.
        // Offering proceed next to it sent agents to "leave the event room", which drops the outcome.
    }

    /**
     * True only when there is really a dialog that a click would advance. Planner and executor must
     * agree on this: offering event_continue for a screen without a dialog is exactly the I4 violation
     * that GremlinMatchGame used to trigger.
     */
    public static boolean eventDialogAdvanceable() {
        try {
            AbstractRoom room = currentRoom();
            if (room == null || room.event == null) {
                return false;
            }
            if (spirelink.events.MatchGame.active()) {
                return false;
            }
            Object words = spirelink.util.FieldKit.staticFieldOrNull(
                    com.megacrit.cardcrawl.events.GenericEventDialog.class, "words");
            if (words instanceof List && !((List<?>) words).isEmpty()) {
                return true;
            }
            List<LargeDialogOptionButton> options = dialogOptions();
            return options != null && !options.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** GremlinMatchGame: one action per face-down card plus a way to give up and leave. */
    private static void matchGame(List<ActionSpec> out) {
        if (!spirelink.events.MatchGame.playable()) {
            // Double safety: a flip is only meaningful on the PLAY screen.
            if (eventDialogAdvanceable()) {
                add(out, ActionTypes.EVENT_CONTINUE, "推进小游戏对话", new JsonObject());
            }
            return;
        }
        java.util.List<AbstractCard> board = spirelink.events.MatchGame.cards();
        AbstractCard chosen = spirelink.events.MatchGame.chosenCard();
        boolean oneFaceUp = Boolean.TRUE.equals(spirelink.util.FieldKit.fieldOrNull(
                spirelink.events.MatchGame.current(), "cardFlipped"));
        if (spirelink.events.MatchGame.flipStalledMs() > 8000L) {
            // Watchdog: the board has not moved for 8 s. event_continue is not executable here
            // (no dialog), so offer the only honest exit - and say it costs the reward.
            spirelink.util.Diag.warn("match-game: 状态 " + spirelink.events.MatchGame.flipStalledMs()
                    + "ms 未变化（oneFaceUp=" + oneFaceUp + "），给放弃动作");
            // Extra exit only: the picks below stay available, so an agent that is just thinking is
            // not pushed into giving the reward up.
            add(out, ActionTypes.PROCEED, "放弃小游戏并离开（会损失奖励）", new JsonObject());
        }
        // cardFlipped == true means "one card is face up, waiting for its pair" - that is exactly when
        // the second click must be allowed (blocking it deadlocked every game at 1 card).
        if (!spirelink.events.MatchGame.gameDone()) {
            for (int i = 0; i < board.size(); i++) {
                AbstractCard card = board.get(i);
                if (card == null || spirelink.events.MatchGame.matched(card) || card == chosen) {
                    continue;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("index", i);
                // 0.1.5: 只有翻开过的牌才给名字，其余是「背面朝上」——见 MatchGame.nameFor。
                add(out, ActionTypes.CHOOSE_MATCH_CARD,
                        "翻牌 #" + i + " " + clean(spirelink.events.MatchGame.nameFor(card)),
                        payload);
            }
        }
        if (spirelink.events.MatchGame.gameDone()) {
            add(out, ActionTypes.PROCEED, "完成小游戏", new JsonObject());
            return;
        }
        // Not playable yet (INTRO / RULE_EXPLANATION / flip animation): advance the dialog, never leave.
        if (eventDialogAdvanceable()) {
            add(out, ActionTypes.EVENT_CONTINUE, "推进小游戏对话", new JsonObject());
        }
    }

    private static volatile long lastCustomDiagAt;

    /**
     * 0.5 s diagnostics for events that draw their own button (GremlinWheelGame). Logs only when the
     * event actually has those fields, so ordinary events stay quiet.
     */
    public static void logCustomButtonState() {
        long now = System.currentTimeMillis();
        if (now - lastCustomDiagAt < 500L) {
            return;
        }
        try {
            AbstractRoom room = currentRoom();
            com.megacrit.cardcrawl.events.AbstractEvent event = room == null ? null : room.event;
            if (event == null || !spirelink.util.FieldKit.hasField(event, "startSpin")) {
                return;
            }
            lastCustomDiagAt = now;
            spirelink.util.Diag.info("custom-button: class=" + event.getClass().getSimpleName()
                    + " startSpin=" + spirelink.util.FieldKit.peek(event, "startSpin")
                    + " buttonPressed=" + spirelink.util.FieldKit.peek(event, "buttonPressed")
                    + " bounceTimer=" + spirelink.util.FieldKit.peek(event, "bounceTimer")
                    + " animTimer=" + spirelink.util.FieldKit.peek(event, "animTimer")
                    + " wheelAngle=" + spirelink.util.FieldKit.peek(event, "wheelAngle")
                    + " result=" + spirelink.util.FieldKit.peek(event, "result")
                    + " finishSpin=" + spirelink.util.FieldKit.peek(event, "finishSpin")
                    + " doneSpinning=" + spirelink.util.FieldKit.peek(event, "doneSpinning")
                    + " screen=" + spirelink.util.FieldKit.peek(event, "screen"));
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static volatile long lastEventDiagAt;

    /** Rate-limited diagnosis for an event that offers no readable option. */
    private static void eventDiag(int dialogCount) {
        long now = System.currentTimeMillis();
        if (now - lastEventDiagAt < 1000L) {
            return;
        }
        lastEventDiagAt = now;
        try {
            AbstractRoom room = currentRoom();
            com.megacrit.cardcrawl.events.AbstractEvent event =
                    room == null ? null : room.event;
            List<?> staticList = spirelink.util.FieldKit.staticFieldOrNull(
                    RoomEventDialog.class, "optionList") instanceof List
                    ? (List<?>) spirelink.util.FieldKit.staticFieldOrNull(
                            RoomEventDialog.class, "optionList") : null;
            Object waitForInput = spirelink.util.FieldKit.staticFieldOrNull(
                    RoomEventDialog.class, "waitForInput");
            Object roomText = event == null ? null : event.roomEventText;
            Object waitTimer = spirelink.util.FieldKit.fieldOrNull(roomText, "waitTimer");
            Object textDone = spirelink.util.FieldKit.fieldOrNull(roomText, "textDone");
            Object roomTextShow = spirelink.util.FieldKit.fieldOrNull(roomText, "show");
            Object screen = spirelink.util.FieldKit.fieldOrNull(
                    event == null ? null : event.imageEventText, "screen");
            spirelink.util.Diag.info("event-wait: class="
                    + (event == null ? "null" : event.getClass().getSimpleName())
                    + " hasDialog=" + (event != null && event.roomEventText != null)
                    + " dialogOptions=" + dialogCount
                    + " roomOptionList=" + (staticList == null ? -1 : staticList.size())
                    + " waitForInput=" + waitForInput
                    + " waitTimer=" + waitTimer
                    + " roomTextShow=" + roomTextShow
                    + " textDone=" + textDone
                    + " screen=" + screen
                    + " phase=" + (room == null ? "null" : room.phase));
        } catch (Throwable t) {
            spirelink.util.Diag.warn("event-wait 诊断失败: " + t);
        }
    }

    private static void grid(List<ActionSpec> out) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        if (screen == null) {
            return;
        }
        // Never offer a card once enough are selected: the executor would reject it, and
        // "legal_actions may not be rejected" is one of our invariants (Colosseum hit this).
        int selected = screen.selectedCards == null ? 0 : screen.selectedCards.size();
        int wanted = gridNumberToSelect(screen);
        // Second signal: once the screen has an upgrade preview the pick is already made (event-opened
        // grids such as MindBloom / MysteriousSphere / SecretPortal report selected=0 while done).
        Object upgradePreview = spirelink.util.FieldKit.fieldOrNull(screen, "upgradePreviewCard");
        // The same GridCardSelectScreen instance is reused for every grid in a run, so a leftover
        // upgradePreviewCard from an earlier upgrade grid used to hide the picks of unrelated grids
        // (Bonfire: forPurge with 14 deck cards, selected=0, nothing offered -> deadlock).
        boolean forUpgrade = Boolean.TRUE.equals(
                spirelink.util.FieldKit.fieldOrNull(screen, "forUpgrade"));
        boolean previewDone = upgradePreview != null && forUpgrade;
        // (previewPending below mirrors the confirm rule for the planner)
        boolean canSelectMore = (wanted <= 0 || selected < wanted) && !previewDone;
        gridDiag(selected, wanted, upgradePreview);
        if (canSelectMore && screen.targetGroup != null && screen.targetGroup.group != null) {
            for (AbstractCard card : screen.targetGroup.group) {
                if (card == null || (screen.selectedCards != null && screen.selectedCards.contains(card))) {
                    continue;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("uuid", String.valueOf(card.uuid));
                add(out, ActionTypes.CHOOSE_GRID_CARDS, clean(card.name), payload);
            }
        }
        // Selection and confirmation are two separate steps in vanilla (smith waits for confirm),
        // so the confirm action must always be offered while the button is usable.
        boolean pickedEnough = wanted > 0 && selected >= wanted;
        Object justForConfirming = spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming");
        // Offer confirm whenever there is something to commit: a full selection, a pending pick on a
        // multi-select grid, an upgrade preview, or the "just for confirming" flavour. C7-03: the exact
        // same predicate runs in ActionExecutor#gridConfirm (GridConfirmPolicy), so a confirm that is
        // not offered here is rejected there as ILLEGAL_ACTION instead of NOT_READY.
        boolean previewPending = upgradePreview != null;
        boolean confirmOffered = GridConfirmPolicy.confirmAvailable(gridConfirmAvailable(screen),
                wanted, selected, Boolean.TRUE.equals(justForConfirming), previewPending);
        if (confirmOffered) {
            add(out, ActionTypes.CONFIRM_GRID, pickedEnough ? "确认（已选够）" : "确认",
                    new JsonObject());
        }
        // Issue #96: advertise cancel_grid only when this grid really has a CancelButton. The old code
        // appended a "never a trap" cancel to every grid, so a confirm-only grid (Transmogrifier's
        // transform, Designer's upgrade/purge) offered a cancel whose closeCurrentScreen() the event
        // read as "the selection finished" - the deck stayed untouched while the event settled.
        boolean anySelectable = canSelectMore && screen.targetGroup != null
                && screen.targetGroup.group != null && !screen.targetGroup.group.isEmpty();
        boolean canCancel = gridCanCancel(screen);
        if (GridCancelPolicy.cancelAllowed(canCancel, anySelectable, confirmOffered)) {
            add(out, ActionTypes.CANCEL_GRID, canCancel ? "取消" : "取消（兜底退出）", new JsonObject());
            if (!canCancel) {
                spirelink.util.Diag.warn("grid: 确认式网格且没有可选/可确认动作，兜底给 cancel_grid "
                        + gridDump(screen));
            }
        }
    }

    /**
     * {@code GridCardSelectScreen.canCancel} — the flag vanilla itself uses to show/hide the cancel
     * button. Falls back to {@code cancelWasOn} when the private field cannot be read.
     */
    static boolean gridCanCancel(GridCardSelectScreen screen) {
        if (screen == null) {
            return false;
        }
        Object value = spirelink.util.FieldKit.fieldOrNull(screen, "canCancel");
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return screen.cancelWasOn;
    }

    /** One-line grid state dump for diagnostics. */
    private static String gridDump(GridCardSelectScreen screen) {
        try {
            return "selected=" + (screen.selectedCards == null ? -1 : screen.selectedCards.size())
                    + " targetGroup=" + (screen.targetGroup == null ? -1 : screen.targetGroup.size())
                    + " confirmScreenUp="
                    + spirelink.util.FieldKit.fieldOrNull(screen, "confirmScreenUp")
                    + " justForConfirming="
                    + spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming")
                    + " cancelWasOn=" + screen.cancelWasOn
                    + " canCancel=" + spirelink.util.FieldKit.fieldOrNull(screen, "canCancel")
                    + " forUpgrade=" + spirelink.util.FieldKit.fieldOrNull(screen, "forUpgrade")
                    + " forPurge=" + spirelink.util.FieldKit.fieldOrNull(screen, "forPurge")
                    + " forTransform=" + spirelink.util.FieldKit.fieldOrNull(screen, "forTransform")
                    + " numCardsToSelect="
                    + spirelink.util.FieldKit.fieldOrNull(screen, "numCardsToSelect");
        } catch (Throwable t) {
            return "dump_failed:" + t;
        }
    }

    private static volatile long lastGridDiagAt;

    /** 1/s diagnostics for the grid: which numbers the planner actually sees. */
    private static void gridDiag(int selected, int wanted, Object upgradePreview) {
        long now = System.currentTimeMillis();
        if (now - lastGridDiagAt < 1000L) {
            return;
        }
        lastGridDiagAt = now;
        try {
            GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
            if (screen == null) {
                return;
            }
            Object confirmDisabled = screen.confirmButton == null ? null
                    : spirelink.util.FieldKit.fieldOrNull(screen.confirmButton, "isDisabled");
            spirelink.util.Diag.info("grid: selected=" + selected + " wanted=" + wanted
                    + " targetGroup=" + (screen.targetGroup == null ? -1
                            : screen.targetGroup.size())
                    + " confirmDisabled=" + confirmDisabled
                    + " confirmScreenUp=" + screen.confirmScreenUp
                    + " upgradePreview=" + (upgradePreview == null ? "null"
                            : upgradePreview.getClass().getSimpleName()));
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /** How many cards the grid wants (CommunicationMod calls this numCardsToSelect). */
    private static int gridNumberToSelect(GridCardSelectScreen screen) {
        Object num = spirelink.util.FieldKit.fieldOrNull(screen, "numCardsToSelect");
        if (num instanceof Number) {
            return ((Number) num).intValue();
        }
        num = spirelink.util.FieldKit.fieldOrNull(screen, "numCards");
        return num instanceof Number ? ((Number) num).intValue() : -1;
    }

    /**
     * Same idea as CommunicationMod's isGridScreenConfirmAvailable. Package visible because
     * {@code ActionExecutor#gridConfirm} feeds it into the same {@link GridConfirmPolicy} call (C7-03).
     */
    static boolean gridConfirmAvailable(GridCardSelectScreen screen) {
        Object confirmScreenUp = spirelink.util.FieldKit.fieldOrNull(screen, "confirmScreenUp");
        Object justForConfirming = spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming");
        if (Boolean.TRUE.equals(confirmScreenUp) || Boolean.TRUE.equals(justForConfirming)) {
            return true;
        }
        if (screen.confirmButton == null) {
            return false;
        }
        Object disabled = spirelink.util.FieldKit.fieldOrNull(screen.confirmButton, "isDisabled");
        Object hidden = spirelink.util.FieldKit.fieldOrNull(screen.confirmButton, "isHidden");
        return !Boolean.TRUE.equals(disabled) && !Boolean.TRUE.equals(hidden);
    }

    private static void runOver(List<ActionSpec> out) {
        add(out, ActionTypes.PROCEED, "回主菜单", new JsonObject());
    }

    /** Post-death unlock/settings/credits screens: the only way forward is back to the main menu. */
    private static void viewScreen(List<ActionSpec> out) {
        add(out, ActionTypes.PROCEED, "返回主菜单", new JsonObject());
    }

    // ---------------------------------------------------------------- hand select

    /** Discard / exhaust / choose-a-card screens driven by {@code HandCardSelectScreen}. */
    /** Shared predicate: the game is waiting for the player to act (single source of truth). */
    public static boolean playerTurnNow() {
        try {
            if (AbstractDungeon.actionManager == null) {
                return false;
            }
            return AbstractDungeon.actionManager.phase
                    == com.megacrit.cardcrawl.actions.GameActionManager.Phase.WAITING_ON_USER;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One hand card as {@link #handSelectActions} sees it: {@code uuid} for the payload, a readable
     * label, and whether the game already marked it.
     */
    static final class HandChoice {

        final String uuid;
        final String label;
        final boolean selected;

        HandChoice(String uuid, String label, boolean selected) {
            this.uuid = uuid;
            this.label = label;
            this.selected = selected;
        }
    }

    /**
     * Pure core of {@code ui=hand_select} (issues #74 / #102), split out of the game-reading wrapper
     * so the three states (nothing picked / partly picked / full) are unit-testable.
     *
     * <p>Issue #74: the screenshot that filed the bug showed exactly one action,
     * {@code confirm_hand_select} — no way to pick a card and no way out.</p>
     *
     * <p>Issue #102: the advertised set and the executor's accepted set must come from the same pure
     * function, so both sides now ask {@link HandSelectPolicy} — which mirrors vanilla's
     * {@code HandCardSelectScreen.update()} click branch exactly. That kills two drifts at once:</p>
     * <ul>
     *   <li>confirm was advertised in states where the click does nothing (a {@code canPickZero}
     *       screen with a partial pick): the action answered ok, nothing changed, and the harness sat
     *       in {@code queued} for 10.6 s and then locked every act for 13 s;</li>
     *   <li>cancel was advertised where the click would apply the picked cards instead of dropping
     *       them (B2-14: "cancel" on a screen with a selection is a confirm in disguise).</li>
     * </ul>
     *
     * <p>Rules: a card can still be picked → one {@code choose_hand_cards} per pickable card; the
     * click would really close the screen → {@code confirm_hand_select}; nothing is selected and that
     * same click would close it → {@code cancel_hand_select}.</p>
     *
     * <p>The screen is still never a dead end: while it is up there is always at least one of
     * {@code choose_hand_cards} (cards left to pick) or {@code confirm_hand_select} (enough picked).
     * The empty-result fallback below keeps the old unconditional cancel as a last resort.</p>
     */
    static void handSelectActions(List<ActionSpec> out, List<HandChoice> hand,
            int numCardsToSelect, int selectedCount, boolean canPickZero, boolean anyNumber,
            boolean upTo, String reason) {
        String hint = reason == null || reason.isEmpty() ? "" : "（" + reason + "）";
        boolean canPickMore = HandSelectPolicy.canPickMore(numCardsToSelect, selectedCount);
        if (canPickMore && hand != null) {
            for (HandChoice card : hand) {
                if (card == null || card.selected || card.uuid == null) {
                    continue;
                }
                JsonObject payload = new JsonObject();
                com.google.gson.JsonArray uuids = new com.google.gson.JsonArray();
                uuids.add(card.uuid);
                payload.add("uuids", uuids);
                add(out, ActionTypes.CHOOSE_HAND_CARDS, "选择 " + card.label + hint, payload);
            }
        }
        if (HandSelectPolicy.canConfirm(numCardsToSelect, selectedCount, canPickZero, anyNumber,
                upTo)) {
            add(out, ActionTypes.CONFIRM_HAND_SELECT, "确认选择", new JsonObject());
        }
        if (HandSelectPolicy.canCancel(numCardsToSelect, selectedCount, canPickZero, anyNumber,
                upTo)) {
            add(out, ActionTypes.CANCEL_HAND_SELECT, "取消选择", new JsonObject());
        }
        if (out.isEmpty()) {
            // Pathological (an open screen with nothing pickable and nothing to confirm): I4 says the
            // ui must never come back empty, so keep the old unconditional cancel as a last resort.
            // The executor answers ILLEGAL_ACTION with the reason — a business error, not a freeze.
            add(out, ActionTypes.CANCEL_HAND_SELECT, "取消选择（兜底退出）", new JsonObject());
            spirelink.util.Diag.warn("hand_select: 没有可选/可确认的动作（need=" + numCardsToSelect
                    + " selected=" + selectedCount + "），兜底给 cancel_hand_select");
        }
    }

    /**
     * {@code HandCardSelectScreen.anyNumber} (private, no getter): "a click on confirm closes the
     * screen whatever the count is". Unreadable = {@code false}, the conservative answer — it can only
     * remove an advertised action, never add one the game would ignore.
     */
    static boolean anyNumber(HandCardSelectScreen screen) {
        Object value = spirelink.util.FieldKit.peek(screen, "anyNumber");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static void handSelect(List<ActionSpec> out) {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        AbstractPlayer player = AbstractDungeon.player;
        if (screen == null || player == null || player.hand == null || player.hand.group == null) {
            return;
        }
        List<HandChoice> hand = new ArrayList<HandChoice>();
        for (AbstractCard card : player.hand.group) {
            if (card == null || card.uuid == null) {
                continue;
            }
            boolean selected = screen.selectedCards != null && screen.selectedCards.contains(card);
            hand.add(new HandChoice(card.uuid.toString(), card.name, selected));
        }
        handSelectActions(out, hand, screen.numCardsToSelect,
                screen.selectedCards == null ? 0 : screen.selectedCards.size(),
                screen.canPickZero, anyNumber(screen), screen.upTo,
                clean(screen.selectionReason));
    }

    /** Strips the game's {@code #r}/{@code NL} markup for labels. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("#.", "").replace("NL", " ")
                .replace("[", "").replace("]", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 39) + "…";
        }
        return cleaned;
    }

    // ---------------------------------------------------------------- map

    private static void map(List<ActionSpec> out) {
        List<MapRoomNode> reachable = reachableNodes();
        AbstractRoom mapRoom = currentRoom();
        // Closing the map is only a real move when there is an unfinished room to go back to.
        boolean canReturnToRoom = mapRoom != null
                && mapRoom.phase != AbstractRoom.RoomPhase.COMPLETE;
        int decision = MapExitPolicy.decide(reachable.size(), bossAvailable(), canReturnToRoom);
        if (decision == MapExitPolicy.PICK_BOSS) {
            // Last row cleared: the only way forward is the boss icon (a hitbox, not a node).
            JsonObject payload = new JsonObject();
            payload.addProperty("x", -1);
            payload.addProperty("y", AbstractDungeon.map.size());
            add(out, ActionTypes.CHOOSE_MAP_NODE, "前往 Boss", payload);
            spirelink.util.Diag.info("地图: 给出 Boss 入口动作");
            return;
        }
        if (MapExitPolicy.proceedClosesMap(decision)) {
            // Nothing to walk to and no boss yet, but the room is not finished: closing the map is the
            // only real move (the executor closes it). Same decision function as the executor's
            // proceed branch, so this action is always executable.
            add(out, ActionTypes.PROCEED, "关闭地图", new JsonObject());
            spirelink.util.Diag.info("地图: " + MapExitPolicy.message(decision, 0, 0));
        } else if (decision == MapExitPolicy.DEAD_END) {
            // Issue #100 (S1): a lone proceed here was a soft lock - the executor never closed the map
            // in that state, so the only advertised action answered "queued" forever. Offer nothing
            // that cannot land; abandon_mid_run below stays as the honest exit.
            spirelink.util.Diag.warn("地图: " + MapExitPolicy.message(decision, 0, 0) + " | " + mapDump());
        }
        // Only read the map grid when there is something to describe: reachable is empty on a dead
        // end, where map() must still hand out abandon_mid_run (and AbstractDungeon.map may be null).
        int width = 7;
        if (!reachable.isEmpty() && AbstractDungeon.map != null && !AbstractDungeon.map.isEmpty()
                && AbstractDungeon.map.get(0) != null) {
            width = AbstractDungeon.map.get(0).size();
        }
        for (MapRoomNode node : reachable) {
            JsonObject payload = new JsonObject();
            payload.addProperty("x", node.x);
            payload.addProperty("y", node.y);
            String roomClass = node.room == null ? "" : node.room.getClass().getSimpleName();
            String roomZh = spirelink.state.UiNames.roomZh(roomClass);
            String columnZh = spirelink.state.UiNames.columnZh(node.x, width);
            // Human readable on purpose: "前往 第3行 · 精英（左2）" is what a player would say, and the
            // HUD/agent transcripts are read by humans.
            payload.addProperty("room", roomZh);
            payload.addProperty("column", node.x);
            payload.addProperty("column_label", columnZh);
            payload.addProperty("row", node.y + 1);
            add(out, ActionTypes.CHOOSE_MAP_NODE, "前往 第" + (node.y + 1) + "行 · " + roomZh
                    + "（" + columnZh + "）", payload);
        }
        // The map is the natural place to give up on a run: it is the only screen where the agent is
        // not in the middle of something. The executor accepts this anywhere in a run.
        add(out, ActionTypes.ABANDON_MID_RUN, "放弃本局（回主菜单）", new JsonObject());
    }

    /**
     * Nodes the player may move to right now. On the first floor there is no current node yet, so
     * the whole bottom row is offered; afterwards only edges of the current node (including
     * Winged Boots shortcuts) count.
     */
    public static List<MapRoomNode> reachableNodes() {
        List<MapRoomNode> out = new ArrayList<>();
        if (AbstractDungeon.map == null) {
            return out;
        }
        // Until the first move the whole bottom row is open (the Neow node has no edges, so the
        // connection test below would return nothing and the map would look empty).
        if (!AbstractDungeon.firstRoomChosen) {
            // Only bottom-row nodes with an outgoing edge: a node without edges is a dead end (the
            // room gets cleared, then no next node is reachable and the run is stuck forever).
            for (MapRoomNode node : AbstractDungeon.map.get(0)) {
                // Boss-row nodes never have edges; a bottom-row boss must stay walkable.
                boolean bossNode = node != null
                        && node.room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
                if (node != null && !node.taken && (edgesOf(node) > 0 || bossNode)) {
                    out.add(node);
                }
            }
            if (out.isEmpty()) {
                for (MapRoomNode node : AbstractDungeon.map.get(0)) {
                    if (node != null && !node.taken) {
                        out.add(node);
                    }
                }
                spirelink.util.Diag.warn("地图: 底排没有带出边的节点，退回整排候选（可能无路可走）: "
                        + mapDump());
            }
            return out;
        }
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        if (current == null) {
            return out;
        }
        for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
            for (MapRoomNode node : row) {
                if (node == null || node.taken) {
                    continue;
                }
                // Strictly upward only: same-row neighbours are "connected" for travel but walking to
                // them never advances the run (and older builds looped there).
                if (node.y <= current.y) {
                    continue;
                }
                if (current.isConnectedTo(node) || current.wingedIsConnectedTo(node)) {
                    out.add(node);
                }
            }
        }
        if (out.isEmpty() && current != null && current.y >= AbstractDungeon.map.size() - 1) {
            // The boss is not a MapRoomNode (it is DungeonMap.bossHb), so nothing can be added here;
            // map() offers the boss entry action instead.
            spirelink.util.Diag.info("地图: 已在末行，没有更高层节点；Boss 可进=" + bossAvailable());
        }
        if (out.isEmpty()) {
            // Never guess a node: sending the player to an unreachable one wedges the game (the
            // room transition starts on a node with no edge and the render loop stops). Report the
            // blockage instead and let the caller wait, exactly like any other "nothing to do".
            //
            // Issue #126 · C17-04: during an act change the new map exists but AbstractDungeon's current
            // node is still the (-1, *) placeholder with no edges, so "no reachable node" is a transition
            // that heals itself in about a second - QA saw this WARN three times per act change. That
            // case stays silent (the state layer already answers awaiting:"transition"), and the real
            // blockage is rate-limited to one line per 5 s.
            if (current.y >= 0 && emptyMapWarnDue()) {
                spirelink.util.Diag.warn("地图规划为空（不做兜底猜测）: " + mapDump());
            }
        }
        return out;
    }


    /** Issue #126 · C17-04: last time the empty-map WARN was actually printed. */
    private static long lastEmptyMapWarnAt;

    /** Issue #126 · C17-04: at most one empty-map WARN per 5 s. */
    private static boolean emptyMapWarnDue() {
        long now = System.currentTimeMillis();
        if (now - lastEmptyMapWarnAt < 5000L) {
            return false;
        }
        lastEmptyMapWarnAt = now;
        return true;
    }

    /** True when the boss icon exists on the map (it is DungeonMap.bossHb, not a node). */
    public static boolean bossPresent() {
        try {
            return AbstractDungeon.dungeonMapScreen != null
                    && AbstractDungeon.dungeonMapScreen.map != null
                    && AbstractDungeon.dungeonMapScreen.map.bossHb != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the boss can actually be entered right now: on the last row, map open and the room
     * marked COMPLETE (DungeonMap.update() checks all three before accepting a click).
     */
    public static boolean bossAvailable() {
        try {
            if (!bossPresent()) {
                return false;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMPLETE) {
                return false;
            }
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
                return false;
            }
            com.megacrit.cardcrawl.map.MapRoomNode current = AbstractDungeon.getCurrMapNode();
            return current != null && AbstractDungeon.map != null
                    && current.y == AbstractDungeon.map.size() - 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Size of a node's edge list, read reflectively ({@code edges} is private). */
    public static int edgesOf(MapRoomNode node) {
        if (node == null) {
            return -1;
        }
        try {
            Object edges = basemod.ReflectionHacks.getPrivate(node, MapRoomNode.class, "edges");
            return edges instanceof java.util.List ? ((java.util.List<?>) edges).size() : -1;
        } catch (Throwable t) {
            return -2;
        }
    }

    /** True when {@code getCurrMapNode()} really is the node stored in the map grid. */
    private static boolean identityOk(MapRoomNode curr) {
        try {
            if (curr == null || AbstractDungeon.map == null || curr.y < 0
                    || curr.y >= AbstractDungeon.map.size()) {
                return false;
            }
            ArrayList<MapRoomNode> row = AbstractDungeon.map.get(curr.y);
            if (row == null || curr.x < 0 || curr.x >= row.size()) {
                return false;
            }
            return row.get(curr.x) == curr;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Raw value of the private {@code AbstractDungeon.currMapNode}, to catch a null getter. */
    private static String mapFieldCurr() {
        try {
            Object node = basemod.ReflectionHacks.getPrivateStatic(AbstractDungeon.class, "currMapNode");
            if (node == null) {
                return "null";
            }
            MapRoomNode m = (MapRoomNode) node;
            return "(" + m.x + "," + m.y + ")";
        } catch (Throwable t) {
            return "err";
        }
    }

    /** Diagnosis for "map offers nothing": everything needed to see why edges did not match. */
    public static String mapDump() {
        StringBuilder sb = new StringBuilder();
        try {
            MapRoomNode curr = AbstractDungeon.getCurrMapNode();
            sb.append("map_rows=").append(AbstractDungeon.map == null ? -1 : AbstractDungeon.map.size());
            sb.append(" curr=").append(curr == null ? "null" : "(" + curr.x + "," + curr.y + ")");
            sb.append(" currTakenFlag=").append(curr != null && curr.taken);
            sb.append(" curr_is_null=").append(curr == null);
            sb.append(" curr_edges=").append(edgesOf(curr));
            sb.append(" curr_identity_ok=").append(identityOk(curr));
            sb.append(" mapField_curr=").append(mapFieldCurr());
            sb.append(" firstRoomChosen=").append(AbstractDungeon.firstRoomChosen);
            sb.append(" screen=").append(AbstractDungeon.screen);
            if (AbstractDungeon.map != null) {
                for (int y = 0; y < AbstractDungeon.map.size(); y++) {
                    for (MapRoomNode node : AbstractDungeon.map.get(y)) {
                        if (node == null) {
                            continue;
                        }
                        boolean conn = curr != null && curr.isConnectedTo(node);
                        boolean winged = curr != null && curr.wingedIsConnectedTo(node);
                        if (!conn && !winged && !(curr != null && y == curr.y + 1)) {
                            continue;
                        }
                        sb.append(" [(").append(node.x).append(",").append(node.y).append(")")
                                .append(" taken=").append(node.taken)
                                .append(" room=").append(node.room != null)
                                .append(" conn=").append(conn)
                                .append(" winged=").append(winged).append(']');
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" dump_error=").append(t);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static void add(List<ActionSpec> out, String type, String label, JsonObject payload) {
        out.add(new ActionSpec("a" + (out.size() + 1), type, label, payload));
    }

    /** Last line printed per {@code planInfo} key, so one state never writes the same INFO twice. */
    private static final java.util.Map<String, String> PLAN_INFO_LAST =
            new java.util.HashMap<String, String>();

    /**
     * C7-07: one INFO line per <b>state</b>, not one per {@code plan()} call.
     *
     * <p>{@code ActionPlanner.actionsFor} runs for every {@code get_state} and again for the plan
     * attached to every {@code act} answer, so a state-driven note was reprinted verbatim — the shop
     * reported "药水槽已满" three times for one shelf. Identical text now prints once; a real change
     * (another potion on sale, one slot freed) is a different line and still gets through.</p>
     *
     * @param key     what the line is about; one open line per key
     * @param message the line, or {@code null} when the condition no longer holds (re-arms the key so
     *                the next occurrence is printed again)
     */
    private static void planInfo(String key, String message) {
        synchronized (PLAN_INFO_LAST) {
            if (message == null) {
                PLAN_INFO_LAST.remove(key);
                return;
            }
            if (message.equals(PLAN_INFO_LAST.get(key))) {
                return;
            }
            PLAN_INFO_LAST.put(key, message);
        }
        spirelink.util.Diag.info(message);
    }

    /** True when a tutorial / FTUE tip is currently on screen (blocks progress). */
    public static boolean tipUp() {
        return spirelink.state.StateBuilder.isTutorialTipUp();
    }
}
