package spirelink.actions;

import basemod.ReflectionHacks;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.screens.CombatRewardScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;
import com.megacrit.cardcrawl.ui.FtueTip;
import com.megacrit.cardcrawl.ui.buttons.GotItButton;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Executes one whitelisted action (design 8.1 + P1 opening chain).
 *
 * <p>Uses the game's own buttons: it marks the real {@link Hitbox} as clicked so the button's
 * normal {@code update()} performs its action. That means no window focus, no mouse position and
 * no screenshots are involved — the game window may be covered or in the background.
 *
 * <p>Main thread only.
 */
public final class ActionExecutor {

    /** {@code STALE_VERSION} / {@code ILLEGAL_ACTION} are the two codes the harness acts on. */
    public static final String CODE_ILLEGAL = "ILLEGAL_ACTION";
    public static final String CODE_UNKNOWN = "UNKNOWN_ACTION_TYPE";

    private ActionExecutor() {
    }

    /**
     * Moves the pointer to whatever this action is about to touch, and waits for it to get there.
     *
     * <p>Called from the <i>request</i> thread, not the render thread: the glide is animated by the
     * render loop, so waiting here lets the hand visibly arrive first and only then lets the game
     * change under it. Blocking the render thread instead would freeze the very animation we are
     * waiting for — which is exactly how "the card plays, then the cursor jumps" happens.</p>
     *
     * <p>Never throws: the pointer is decoration and must not affect the action.</p>
     */
    public static void reach(String type, JsonObject payload) {
        try {
            float[] target = cursorTarget(type, payload);
            // Last line of defence: a half-laid-out hitbox (0-y or -9554 style) must never send the
            // fish off-screen. Anything outside the window is treated as "no target" instead.
            if (target != null && saneValues(target[0], target[1]) == null) {
                spirelink.util.Diag.info("cursor: " + type + " 目标越界 "
                        + Math.round(target[0]) + "," + Math.round(target[1]) + "（按无目标处理）");
                target = null;
            }
            long start = System.currentTimeMillis();
            if (target != null) {
                spirelink.ui.AgentCursor.flyTo(target[0], target[1]);
                spirelink.ui.AgentCursor.awaitArrival(700);
            }
            // One line per action: the trace is checkable after the fact ("did every action really
            // move the fish, and to where, and did it get there before the game changed?").
            spirelink.util.Diag.info("cursor: " + type + " -> "
                    + (target == null ? "无目标"
                            : Math.round(target[0]) + "," + Math.round(target[1]))
                    + " 到位 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable ignored) {
            // never let the pointer delay or fail an action
        }
    }

    public static ActionResult execute(String type, JsonObject payload) {
        // Issues #57 / #70: an action the current ui does not allow must come back as a business
        // error, never as a half-applied action or as an NPE reported as INTERNAL. The gate is pure
        // and runs before a single game object is touched; a null result means "go ahead".
        //
        // Where the action is about to happen has to be measured BEFORE it runs anyway: playing a
        // card removes it from the hand, moving on the map re-parents the nodes, and then the hitbox
        // we wanted to point at is already gone.
        // Issue #98: a deck peek (get_state{include:["deck"]}, the documented first move) replaces the
        // screen with a view (MASTER_DECK_VIEW &co) which reads as ui=tutorial_tip, while legal_actions
        // still reports the ui underneath (grid / hand_select). Closing the peek *before* the gate is
        // what makes the advertised action executable instead of answering
        // "ui=tutorial_tip 下不能执行 choose_grid_cards（可用: dismiss_tip）".
        // dismiss_tip is exempt: it is the one action whose target *is* the peek.
        if (!ActionTypes.DISMISS_TIP.equals(type)) {
            spirelink.ui.DeckPeek.closeForced("before gate:" + type);
        }
        ActionResult result = gate(type);
        if (result == null) {
            result = executeInner(type, payload);
        }
        // One human sentence per successful action, right where every caller gets its answer.
        if (result != null && result.ok) {
            try {
                spirelink.ui.AgentHud.action(spirelink.ui.HudPhrases.done(type, payload));
            } catch (Throwable ignored) {
                // HUD must never break an action
            }
            try {
                boolean gridPick = ActionTypes.CHOOSE_GRID_CARDS.equals(type)
                        || ActionTypes.CHOOSE_HAND_CARDS.equals(type);
                spirelink.ui.AgentCursor.acted(gridPick);
                spirelink.ui.AgentCursor.setUnavailable(false);
            } catch (Throwable ignored) {
                // the pointer is decoration; never let it fail an action
            }
        } else if (result != null) {
            try {
                spirelink.ui.AgentCursor.rejected();
            } catch (Throwable ignored) {
                // ditto
            }
        }
        return result;
    }

    /**
     * Screen coordinates of whatever this action is going to touch, or {@code null}.
     *
     * <p>Only the actions whose target a human would see are mapped: cards, map nodes, the end-turn
     * button, event options and rewards. Anything else just gets the caption.</p>
     */
    /** Centre of a main-menu entry ("单人游戏", "继续", …) chosen by its click result. */
    private static float[] menuButton(
            com.megacrit.cardcrawl.screens.mainMenu.MenuButton.ClickResult wanted) {
        com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu =
                com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen;
        if (menu == null || menu.buttons == null) {
            return null;
        }
        for (com.megacrit.cardcrawl.screens.mainMenu.MenuButton button : menu.buttons) {
            if (button != null && button.hb != null && button.result == wanted) {
                return new float[]{button.hb.cX, button.hb.cY};
            }
        }
        return null;
    }

    private static float[] cursorTarget(String type, JsonObject payload) {
        JsonObject args = payload == null ? new JsonObject() : payload;
        try {
            switch (type) {
                case ActionTypes.PLAY_CARD: {
                    // play_card identifies the card by uuid (CardPlayRequest), not by index.
                    AbstractPlayer player = AbstractDungeon.player;
                    if (player == null || player.hand == null) {
                        return null;
                    }
                    String uuid = str(args, "uuid", "");
                    if (uuid.isEmpty()) {
                        return null;
                    }
                    for (AbstractCard card : player.hand.group) {
                        if (card == null || card.hb == null || card.uuid == null) {
                            continue;
                        }
                        if (card.uuid.toString().equals(uuid)) {
                            return new float[]{card.hb.cX, card.hb.cY};
                        }
                    }
                    return null;
                }
                case ActionTypes.CHOOSE_MAP_NODE: {
                    int x = num(args, "x", -1);
                    int y = num(args, "y", -1);
                    if (AbstractDungeon.map == null || y < 0 || y >= AbstractDungeon.map.size()
                            || AbstractDungeon.map.get(y) == null || x < 0
                            || x >= AbstractDungeon.map.get(y).size()) {
                        return null;
                    }
                    com.megacrit.cardcrawl.map.MapRoomNode node = AbstractDungeon.map.get(y).get(x);
                    if (node == null || node.hb == null) {
                        return null;
                    }
                    // 地图会滚动：目标节点常在可视区外（y<0 或 y>H），坐标本身是合法的。
                    // 夹到窗口边缘，鱼指向「那个方向的那一格」，而不是干脆不动。
                    float w = com.megacrit.cardcrawl.core.Settings.WIDTH;
                    float h = com.megacrit.cardcrawl.core.Settings.HEIGHT;
                    float nx = Math.max(40f, Math.min(w - 40f, node.hb.cX));
                    float ny = Math.max(40f, Math.min(h - 40f, node.hb.cY));
                    return new float[]{nx, ny};
                }
                case ActionTypes.OPEN_MODE_SELECT:
                    return menuButton(com.megacrit.cardcrawl.screens.mainMenu.MenuButton
                            .ClickResult.PLAY);
                case ActionTypes.CONTINUE_RUN:
                    return menuButton(com.megacrit.cardcrawl.screens.mainMenu.MenuButton
                            .ClickResult.RESUME_GAME);
                case ActionTypes.CHOOSE_CHARACTER: {
                    com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu =
                            com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen;
                    if (menu == null || menu.charSelectScreen == null
                            || menu.charSelectScreen.options == null) {
                        return null;
                    }
                    String wanted = str(args, "character", "");
                    for (com.megacrit.cardcrawl.screens.charSelect.CharacterOption option
                            : menu.charSelectScreen.options) {
                        if (option == null || option.hb == null || option.c == null) {
                            continue;
                        }
                        String id = option.c.chosenClass == null ? ""
                                : option.c.chosenClass.name();
                        if (!wanted.isEmpty() && (id.equalsIgnoreCase(wanted)
                                || option.c.name.equalsIgnoreCase(wanted))) {
                            float[] hb = saneValues(option.hb.cX, option.hb.cY);
                            if (hb != null) {
                                return hb;
                            }
                        }
                    }
                    return null;
                }
                case ActionTypes.EMBARK: {
                    com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu =
                            com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen;
                    if (menu == null || menu.charSelectScreen == null
                            || menu.charSelectScreen.confirmButton == null
                            || menu.charSelectScreen.confirmButton.hb == null) {
                        return null;
                    }
                    return new float[]{menu.charSelectScreen.confirmButton.hb.cX,
                            menu.charSelectScreen.confirmButton.hb.cY};
                }
                case ActionTypes.END_TURN: {
                    if (AbstractDungeon.overlayMenu == null
                            || AbstractDungeon.overlayMenu.endTurnButton == null) {
                        return null;
                    }
                    com.megacrit.cardcrawl.helpers.Hitbox hb = ReflectionHacks.getPrivate(
                            AbstractDungeon.overlayMenu.endTurnButton,
                            com.megacrit.cardcrawl.ui.buttons.EndTurnButton.class, "hb");
                    return hb == null ? null : new float[]{hb.cX, hb.cY};
                }
                case ActionTypes.CHOOSE_OPTION:
                    // 和 choose_neow_reward 走同一条路：选项可能还没排版（hb 为空 / 坐标是 -9554），
                    // 这里必须兜底，否则「点了但指针没动」。
                    return dialogOptionTarget(num(args, "index", -1));
                case ActionTypes.TAKE_REWARD: {
                    com.megacrit.cardcrawl.screens.CombatRewardScreen screen =
                            AbstractDungeon.combatRewardScreen;
                    int index = num(args, "index", num(args, "slot", -1));
                    if (screen == null || screen.rewards == null || index < 0
                            || index >= screen.rewards.size() || screen.rewards.get(index) == null
                            || screen.rewards.get(index).hb == null) {
                        return null;
                    }
                    return new float[]{screen.rewards.get(index).hb.cX,
                            screen.rewards.get(index).hb.cY};
                }
                // ---- everything below exists so that EVERY action carries a visible mouse move:
                // a human watching the screen must be able to point at what the agent just touched.
                case ActionTypes.USE_POTION:
                case ActionTypes.DISCARD_POTION:
                    return potionTarget(args);
                case ActionTypes.TAKE_CARD:
                    return cardRewardTarget(args);
                case ActionTypes.SKIP_CARD:
                    return hbOf(spirelink.util.FieldKit.fieldOrNull(
                            AbstractDungeon.cardRewardScreen, "skipButton"));
                case ActionTypes.CLAIM_RELIC:
                    return bossRelicTarget(args);
                case ActionTypes.SKIP_RELIC:
                    return hbOf(spirelink.util.FieldKit.fieldOrNull(
                            AbstractDungeon.bossRelicScreen, "cancelButton"));
                case ActionTypes.REST:
                case ActionTypes.SMITH:
                case ActionTypes.LIFT:
                case ActionTypes.TOKE:
                    return campfireTarget(type);
                case ActionTypes.BUY:
                    return shopTarget(args);
                case ActionTypes.LEAVE:
                case ActionTypes.PROCEED:
                    return proceedButtonTarget();
                case ActionTypes.CHOOSE_NEOW_REWARD:
                    return dialogOptionTarget(num(args, "index", -1));
                case ActionTypes.EVENT_CONTINUE:
                    return eventTextTarget();
                case ActionTypes.CHOOSE_GRID_CARDS:
                    return gridCardTarget(args);
                case ActionTypes.CONFIRM_GRID:
                    return AbstractDungeon.gridSelectScreen == null ? null
                            : hbOf(AbstractDungeon.gridSelectScreen.confirmButton);
                case ActionTypes.CANCEL_GRID:
                    return cancelButtonTarget();
                case ActionTypes.CHOOSE_HAND_CARDS:
                    return handCardTarget(args);
                case ActionTypes.CONFIRM_HAND_SELECT:
                case ActionTypes.CANCEL_HAND_SELECT:
                    return AbstractDungeon.handCardSelectScreen == null ? null
                            : hbOf(AbstractDungeon.handCardSelectScreen.button);
                case ActionTypes.CHOOSE_MATCH_CARD:
                    return matchCardTarget(args);
                case ActionTypes.OPEN_SHOP:
                    return merchantTarget();
                case ActionTypes.PURGE_CARD:
                    return purgeTarget();
                case ActionTypes.OPEN_CHEST:
                    return chestTarget();
                case ActionTypes.CHOOSE_ASCENSION:
                    return ascensionTarget(args);
                case ActionTypes.ABANDON_RUN:
                    return menuButton(MenuButton.ClickResult.ABANDON_RUN);
                case ActionTypes.BACK:
                    return backButtonTarget();
                case ActionTypes.DISMISS_TIP:
                    return hbOf(spirelink.util.FieldKit.fieldOrNull(AbstractDungeon.ftue, "button"));
                case ActionTypes.CONFIRM_ABANDON:
                case ActionTypes.CANCEL_ABANDON:
                    return abandonPopupTarget(type);
                case ActionTypes.ABANDON_MID_RUN:
                    // The pause menu's "放弃当前游戏" lives behind the top-right gear; that gear is the
                    // first thing a human clicks, so that is where the fish goes.
                    // The offset must scale with the window (46 px gear at 1080p ≈ 31 px at 720p),
                    // otherwise the fish lands left of the gear on small windows and inside the
                    // button's shadow at 4K.
                    float gearScale = com.megacrit.cardcrawl.core.Settings.HEIGHT / 1080f;
                    return new float[]{com.megacrit.cardcrawl.core.Settings.WIDTH - 70f * gearScale,
                            com.megacrit.cardcrawl.core.Settings.HEIGHT - 45f * gearScale};
                case ActionTypes.CHOOSE_MODE:
                    return modeTarget(args);
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Centre of a Hitbox, or of any object that owns one ({@code hb}). */
    private static float[] hbOf(Object owner) {
        if (owner == null) {
            return null;
        }
        Hitbox hb = owner instanceof Hitbox ? (Hitbox) owner
                : (Hitbox) spirelink.util.FieldKit.fieldOrNull(owner, "hb");
        float[] direct = saneOf(hb);
        if (direct != null) {
            return direct;
        }
        // Some screens park the hitbox off-screen and only the draw position is real (merchant
        // cards reported a hitbox outside the window). current_x/current_y is where it is drawn.
        Object cx = spirelink.util.FieldKit.fieldOrNull(owner, "current_x");
        Object cy = spirelink.util.FieldKit.fieldOrNull(owner, "current_y");
        if (cx instanceof Number && cy instanceof Number) {
            float[] drawn = saneValues(((Number) cx).floatValue(), ((Number) cy).floatValue());
            if (drawn != null) {
                return drawn;
            }
        }
        return null;
    }

    /** Same bounds rule as {@link #saneOf}, for a bare coordinate pair. */
    private static float[] saneValues(float x, float y) {
        if (Float.isNaN(x) || Float.isNaN(y)) {
            return null;
        }
        float w = com.megacrit.cardcrawl.core.Settings.WIDTH;
        float h = com.megacrit.cardcrawl.core.Settings.HEIGHT;
        if (x < -64f || y < -64f || x > w + 64f || y > h + 64f) {
            return null;
        }
        return new float[]{x, y};
    }

    /**
     * A hitbox that was never laid out sits at a garbage position (Neow's first option reported
     * -9554,-9960). Flying there would put the fish outside the window, so treat it as "no target"
     * and keep the pointer where it is.
     */
    private static float[] saneOf(Hitbox hb) {
        if (hb == null) {
            return null;
        }
        return saneValues(hb.cX, hb.cY);
    }

    private static float[] potionTarget(JsonObject args) {
        AbstractPlayer player = AbstractDungeon.player;
        int slot = num(args, "slot", -1);
        if (player == null || player.potions == null || slot < 0 || slot >= player.potions.size()) {
            return null;
        }
        return hbOf(player.potions.get(slot));
    }

    private static float[] cardRewardTarget(JsonObject args) {
        CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
        if (screen == null || screen.rewardGroup == null) {
            return null;
        }
        String uuid = str(args, "uuid", "");
        int index = num(args, "index", -1);
        if (!uuid.isEmpty()) {
            for (AbstractCard card : screen.rewardGroup) {
                if (card != null && uuid.equals(String.valueOf(card.uuid))) {
                    return hbOf(card);
                }
            }
        }
        if (index >= 0 && index < screen.rewardGroup.size()) {
            return hbOf(screen.rewardGroup.get(index));
        }
        return null;
    }

    private static float[] bossRelicTarget(JsonObject args) {
        Object relics = spirelink.util.FieldKit.fieldOrNull(AbstractDungeon.bossRelicScreen, "relics");
        int index = num(args, "index", -1);
        if (!(relics instanceof List) || index < 0 || index >= ((List<?>) relics).size()) {
            return null;
        }
        return hbOf(((List<?>) relics).get(index));
    }

    private static float[] campfireTarget(String type) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (!(room instanceof com.megacrit.cardcrawl.rooms.RestRoom)) {
            return null;
        }
        Object ui = ((com.megacrit.cardcrawl.rooms.RestRoom) room).campfireUI;
        Object buttons = spirelink.util.FieldKit.fieldOrNull(ui, "buttons");
        if (!(buttons instanceof List)) {
            return null;
        }
        for (Object option : (List<?>) buttons) {
            if (option != null && type.equals(campfireActionOf(option.getClass().getSimpleName()))) {
                return hbOf(option);
            }
        }
        return null;
    }

    private static float[] shopTarget(JsonObject args) {
        float[] hit = shopItemTarget(args);
        if (hit != null) {
            return hit;
        }
        // ui=shop_room：人还没点商人，货架是空的，卡片/遗物都没排版，自然没有坐标。
        // 这时候鱼该去点的是商人本人——点开之后才有货架可指。
        float[] merchant = merchantTarget();
        if (merchant != null) {
            spirelink.util.Diag.info("shop target: 商店还没开屏，先去点商人");
            return merchant;
        }
        return null;
    }

    private static float[] shopItemTarget(JsonObject args) {
        ShopScreen screen = AbstractDungeon.shopScreen;
        int index = num(args, "index", -1);
        String kind = str(args, "kind", "");
        if (screen == null || index < 0) {
            return null;
        }
        if ("card".equals(kind)) {
            List<AbstractCard> cards = ActionPlanner.shopCards(screen);
            if (index >= cards.size()) {
                spirelink.util.Diag.info("shop target: 卡片列表只有 " + cards.size() + " 张，index=" + index
                        + "（active=" + screen.isActive + "）");
                return null;
            }
            float[] hb = hbOf(cards.get(index));
            if (hb == null) {
                spirelink.util.Diag.info("shop target: 卡片 " + index + " 没有可用坐标 "
                        + spirelink.util.FieldKit.fieldOrNull(cards.get(index), "hb"));
            }
            return hb;
        }
        if ("relic".equals(kind)) {
            List<?> list = ShopAccess.relics(screen);
            return index < list.size()
                    ? hbOf(spirelink.util.FieldKit.fieldOrNull(list.get(index), "relic")) : null;
        }
        if ("potion".equals(kind)) {
            List<?> list = ShopAccess.potions(screen);
            return index < list.size()
                    ? hbOf(spirelink.util.FieldKit.fieldOrNull(list.get(index), "potion")) : null;
        }
        return "purge".equals(kind) ? purgeTarget() : null;
    }

    /** The purge service is drawn at a private (x, y); the cursor just needs that spot. */
    private static float[] purgeTarget() {
        Object x = spirelink.util.FieldKit.fieldOrNull(AbstractDungeon.shopScreen, "purgeCardX");
        Object y = spirelink.util.FieldKit.fieldOrNull(AbstractDungeon.shopScreen, "purgeCardY");
        if (x instanceof Number && y instanceof Number) {
            float[] hb = saneValues(((Number) x).floatValue(), ((Number) y).floatValue());
            if (hb != null) {
                return hb;
            }
        }
        // 商店没开屏时净化按钮还没摆位（初值是 1402,-1000），鱼该去点商人把店打开。
        return merchantTarget();
    }

    private static float[] proceedButtonTarget() {
        return AbstractDungeon.overlayMenu == null ? null
                : hbOf(AbstractDungeon.overlayMenu.proceedButton);
    }

    private static float[] dialogOptionTarget(int index) {
        List<LargeDialogOptionButton> options = ActionPlanner.dialogOptions();
        if (options == null || index < 0 || index >= options.size()) {
            return null;
        }
        LargeDialogOptionButton option = options.get(index);
        float[] hb = hbOf(option);
        if (hb != null) {
            return hb;
        }
        // 还没排版好的选项（涅奥的第一个选项初值是 -9554,-9960，事件刚打开时选项还在从屏幕下方
        // 往上滑）用它自己的绘制坐标兜底；连绘制坐标都在屏幕外，就退到对话框本身——
        // 鱼指向「这块对话框要做的那个选择」，总比原地不动强。
        Object x = spirelink.util.FieldKit.fieldOrNull(option, "x");
        Object y = spirelink.util.FieldKit.fieldOrNull(option, "y");
        float[] drawn = x instanceof Number && y instanceof Number
                ? saneValues(((Number) x).floatValue(), ((Number) y).floatValue()) : null;
        if (drawn != null) {
            spirelink.util.Diag.info("dialog target: 用选项绘制坐标 " + Math.round(drawn[0]) + ","
                    + Math.round(drawn[1]));
            return drawn;
        }
        float[] text = eventTextTarget();
        return text != null ? text
                : new float[]{com.megacrit.cardcrawl.core.Settings.WIDTH * 0.35f,
                        com.megacrit.cardcrawl.core.Settings.HEIGHT * 0.40f};
    }

    private static float[] eventTextTarget() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null) {
            return null;
        }
        float[] hb = room.event == null ? null : hbOf(room.event.imageEventText);
        if (hb != null) {
            return hb;
        }
        // The dialog is a bottom panel: clicking anywhere on it advances the text.
        spirelink.util.Diag.info("dialog target: 退回对话框中心（event="
                + (room.event == null ? "null" : room.event.getClass().getSimpleName()) + "）");
        return new float[]{com.megacrit.cardcrawl.core.Settings.WIDTH * 0.5f,
                com.megacrit.cardcrawl.core.Settings.HEIGHT * 0.24f};
    }

    private static float[] gridCardTarget(JsonObject args) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        if (screen == null || screen.targetGroup == null || screen.targetGroup.group == null) {
            return null;
        }
        String uuid = str(args, "uuid", "");
        int index = num(args, "index", -1);
        if (!uuid.isEmpty()) {
            for (AbstractCard card : screen.targetGroup.group) {
                if (card != null && uuid.equals(String.valueOf(card.uuid))) {
                    return hbOf(card);
                }
            }
        }
        if (index >= 0 && index < screen.targetGroup.group.size()) {
            return hbOf(screen.targetGroup.group.get(index));
        }
        return null;
    }

    private static float[] handCardTarget(JsonObject args) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || player.hand == null) {
            return null;
        }
        // choose_hand_cards 的载荷是 {"uuids": [...]}，不是单个 uuid。
        String uuid = str(args, "uuid", "");
        if (uuid.isEmpty() && args.has("uuids") && args.get("uuids").isJsonArray()
                && args.getAsJsonArray("uuids").size() > 0) {
            uuid = args.getAsJsonArray("uuids").get(0).getAsString();
        }
        int index = num(args, "index", -1);
        if (!uuid.isEmpty()) {
            for (AbstractCard card : player.hand.group) {
                if (card != null && uuid.equals(String.valueOf(card.uuid))) {
                    return hbOf(card);
                }
            }
        }
        if (index >= 0 && index < player.hand.group.size()) {
            return hbOf(player.hand.group.get(index));
        }
        return null;
    }

    private static float[] matchCardTarget(JsonObject args) {
        int index = num(args, "index", -1);
        List<AbstractCard> cards = spirelink.events.MatchGame.cards();
        if (cards == null || index < 0 || index >= cards.size()) {
            return null;
        }
        return hbOf(cards.get(index));
    }

    private static float[] merchantTarget() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (!(room instanceof com.megacrit.cardcrawl.rooms.ShopRoom)) {
            return null;
        }
        return hbOf(((com.megacrit.cardcrawl.rooms.ShopRoom) room).merchant);
    }

    private static float[] chestTarget() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room instanceof com.megacrit.cardcrawl.rooms.TreasureRoom) {
            float[] hb = hbOf(((com.megacrit.cardcrawl.rooms.TreasureRoom) room).chest);
            if (hb != null) {
                return hb;
            }
        }
        return new float[]{com.megacrit.cardcrawl.rewards.chests.AbstractChest.CHEST_LOC_X,
                com.megacrit.cardcrawl.rewards.chests.AbstractChest.CHEST_LOC_Y};
    }

    /** Ascension is set with the little arrows, so the fish goes to the arrow that moves the level. */
    private static float[] ascensionTarget(JsonObject args) {
        CharacterSelectScreen screen = charSelectScreen();
        if (screen == null) {
            return null;
        }
        int wanted = num(args, "level", screen.ascensionLevel);
        String field = wanted >= screen.ascensionLevel ? "ascRightHb" : "ascLeftHb";
        float[] hb = hbOf(spirelink.util.FieldKit.fieldOrNull(screen, field));
        if (hb == null) {
            hb = hbOf(spirelink.util.FieldKit.fieldOrNull(screen, "ascensionModeHb"));
        }
        return hb;
    }

    private static float[] backButtonTarget() {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return null;
        }
        if (menu.screen == MainMenuScreen.CurScreen.CHAR_SELECT && menu.charSelectScreen != null) {
            float[] hb = hbOf(menu.charSelectScreen.cancelButton);
            if (hb != null) {
                return hb;
            }
        }
        return hbOf(spirelink.util.FieldKit.fieldOrNull(menu.panelScreen, "button"));
    }

    private static float[] abandonPopupTarget(String type) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null || menu.abandonPopup == null) {
            return null;
        }
        String field = ActionTypes.CONFIRM_ABANDON.equals(type) ? "yesHb" : "noHb";
        return hbOf(spirelink.util.FieldKit.fieldOrNull(menu.abandonPopup, field));
    }

    private static float[] modeTarget(JsonObject args) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return null;
        }
        MainMenuPanelButton panel = ActionPlanner.findModePanel(menu, str(args, "mode", "standard"));
        float[] hb = hbOf(panel);
        return hb != null ? hb : hbOf(spirelink.util.FieldKit.fieldOrNull(menu.panelScreen, "button"));
    }

    private static float[] cancelButtonTarget() {
        return AbstractDungeon.overlayMenu == null ? null
                : hbOf(AbstractDungeon.overlayMenu.cancelButton);
    }

    private static ActionResult executeInner(String type, JsonObject payload) {
        if (type == null) {
            return ActionResult.reject(CODE_ILLEGAL, "缺少动作类型");
        }
        JsonObject args = payload == null ? new JsonObject() : payload;
        // A peek must never eat a real action: drop it before touching the game state.
        // dismiss_tip keeps it alive, because closing the peek *is* what dismissing it means (#98).
        if (!ActionTypes.DISMISS_TIP.equals(type)) {
            spirelink.ui.DeckPeek.closeForced("forced-close by act:" + type);
        }
        switch (type) {
            case ActionTypes.OPEN_MODE_SELECT:
                return clickMenuButton(MenuButton.ClickResult.PLAY);
            case ActionTypes.CONTINUE_RUN:
                return clickMenuButton(MenuButton.ClickResult.RESUME_GAME);
            case ActionTypes.ABANDON_RUN:
                return clickMenuButton(MenuButton.ClickResult.ABANDON_RUN);
            case ActionTypes.CHOOSE_MODE:
                return chooseMode(args);
            case ActionTypes.CHOOSE_CHARACTER:
                return chooseCharacter(args);
            case ActionTypes.EMBARK:
                return embark();
            case ActionTypes.CHOOSE_ASCENSION:
                return chooseAscension(args);
            case ActionTypes.ABANDON_MID_RUN:
                return abandonMidRun();
            case ActionTypes.BACK:
                return back();
            case ActionTypes.DISMISS_TIP:
                return dismissTip();
            case ActionTypes.CONFIRM_ABANDON:
                return abandonPopup(true);
            case ActionTypes.CANCEL_ABANDON:
                return abandonPopup(false);
            case ActionTypes.PLAY_CARD:
                return playCard(args);
            case ActionTypes.END_TURN:
                return endTurn();
            case ActionTypes.USE_POTION:
                return potion(args, true);
            case ActionTypes.DISCARD_POTION:
                return potion(args, false);
            case ActionTypes.CHOOSE_HAND_CARDS:
                return chooseHandCards(args);
            case ActionTypes.OPEN_CHEST:
                return openChest();
            case ActionTypes.TAKE_REWARD:
                return takeReward(args);
            case ActionTypes.TAKE_CARD:
                return takeCard(args);
            case ActionTypes.SKIP_CARD:
                return skipCard();
            case ActionTypes.CLAIM_RELIC:
                return claimRelic(args);
            case ActionTypes.SKIP_RELIC:
                return skipRelic();
            case ActionTypes.VERIFY_CLAIM_RELIC:
                return verifyClaimRelic(args);
            case ActionTypes.VERIFY_RUN_OVER:
                return verifyRunOver();
            case ActionTypes.VERIFY_ENCOUNTER:
                return verifyEncounter(args);
            case ActionTypes.REST:
            case ActionTypes.SMITH:
            case ActionTypes.TOKE:
            case ActionTypes.LIFT:
                return campfire(type);
            case ActionTypes.BUY:
                return buy(args);
            case ActionTypes.OPEN_SHOP:
                return openShop();
            case ActionTypes.PURGE_CARD:
                return purgeCard();
            case ActionTypes.LEAVE:
                return leaveShop();
            case ActionTypes.EVENT_CONTINUE:
                return eventContinue();
            case ActionTypes.CHOOSE_OPTION:
                return chooseOption(args);
            case ActionTypes.CHOOSE_GRID_CARDS:
                return chooseGridCards(args);
            case ActionTypes.CONFIRM_GRID:
                return gridConfirm(true);
            case ActionTypes.CANCEL_GRID:
                return gridConfirm(false);
            case ActionTypes.CHOOSE_MATCH_CARD:
                return chooseMatchCard(args);
            case ActionTypes.CONFIRM_HAND_SELECT:
                return confirmHandSelect();
            case ActionTypes.CANCEL_HAND_SELECT:
                return cancelHandSelect();
            case ActionTypes.CHOOSE_NEOW_REWARD:
                return chooseNeowReward(args);
            case ActionTypes.PROCEED:
                return proceed();
            case ActionTypes.CHOOSE_MAP_NODE:
                return chooseMapNode(args);
            default:
                return ActionResult.reject(CODE_UNKNOWN, "不认识的动作类型: " + type);
        }
    }

    // ---------------------------------------------------------------- main menu

    private static ActionResult clickMenuButton(MenuButton.ClickResult wanted) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null || menu.buttons == null) {
            return ActionResult.reject(CODE_ILLEGAL, "主菜单还没准备好");
        }
        for (MenuButton button : menu.buttons) {
            if (button != null && button.result == wanted && button.hb != null) {
                button.hb.clicked = true;
                return ActionResult.ok();
            }
        }
        return ActionResult.reject(CODE_ILLEGAL, "主菜单没有这个按钮: " + wanted);
    }

    private static ActionResult chooseMode(JsonObject payload) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        String mode = str(payload, "mode", "standard").toLowerCase(Locale.ROOT);
        if (!"standard".equals(mode) && !"daily".equals(mode) && !"custom".equals(mode)) {
            return ActionResult.rejectParam(CODE_ILLEGAL, "未知模式: " + mode);
        }
        if (menu == null || menu.charSelectScreen == null) {
            return ActionResult.reject(CODE_ILLEGAL, "主菜单还没准备好");
        }
        if ("standard".equals(mode)) {
            // Deterministic path: CharacterSelectScreen.open(true) itself sets
            // MainMenuScreen.screen = CHAR_SELECT, so we do not depend on a panel hitbox click
            // being picked up by the panel's own update (that click only closed the panel).
            if (menu.panelScreen != null) {
                menu.panelScreen.hide();
            }
            menu.charSelectScreen.open(true);
            spirelink.util.Diag.info("choose_mode standard：直接打开选人界面");
            return ActionResult.ok();
        }
        MainMenuPanelButton panel = ActionPlanner.findModePanel(menu, mode);
        if (panel != null) {
            panel.hb.clicked = true;
            return ActionResult.ok();
        }
        return ActionResult.reject(CODE_ILLEGAL, "模式面板里没有 " + mode + "（每日/自定未解锁）");
    }

    // ---------------------------------------------------------------- char select

    private static CharacterSelectScreen charSelectScreen() {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        return menu == null ? null : menu.charSelectScreen;
    }

    private static ActionResult chooseCharacter(JsonObject payload) {
        CharacterSelectScreen screen = charSelectScreen();
        if (screen == null || screen.options == null) {
            return ActionResult.reject(CODE_ILLEGAL, "选人界面还没准备好");
        }
        String wanted = str(payload, "character", "");
        if (wanted.isEmpty()) {
            return ActionResult.rejectParam(CODE_ILLEGAL, "缺少 character");
        }
        String upper = wanted.toUpperCase(Locale.ROOT);
        for (CharacterOption option : screen.options) {
            if (option == null || option.c == null || option.hb == null) {
                continue;
            }
            AbstractPlayer.PlayerClass clazz = option.c.chosenClass;
            if (clazz != null && clazz.name().equals(upper)) {
                if (option.locked) {
                    return ActionResult.rejectParam(CODE_ILLEGAL, "该角色还没解锁");
                }
                if (option.selected) {
                    // Idempotent: re-choosing the already-selected character is a no-op, not a toggle
                    // (a retried action used to flip the selection back and forth).
                    //
                    // #122 · C13-05: this has to be {@link ActionResult#verified()}, not
                    // {@code ok()}. The planner deliberately keeps this row in legal_actions ("保持 X",
                    // payload {@code selected:true}) so a naive first-action harness cannot toggle the
                    // selection - but an {@code ok()} that moves nothing is exactly what ToolCatalog
                    // reports as {@code queued} and then locks with {@code ACTION_IN_FLIGHT} for the
                    // next 3 s: pressing "保持 X" twice stalled every following action for up to 1.68 s
                    // on the real machine. {@code verified()} means "the effect I promise is already
                    // true", so the act answers {@code applied} at once and clears the guard.
                    spirelink.util.Diag.info("choose_character: " + clazz.name()
                            + " 已经是选中状态，幂等返回（不重复点击、不排队）");
                    return ActionResult.verified();
                }
                // Mirror CharacterOption.update()'s click handler exactly (verified against the
                // 12-18-2022 bytecode): deselect others, mark selected, notify the screen, publish
                // the class and enable the confirm button. Clicking the hitbox alone is not
                // reliable because the option only reacts while hovered.
                screen.deselectOtherOptions(option);
                option.selected = true;
                screen.justSelected();
                CardCrawlGame.chosenCharacter = clazz;
                screen.confirmButton.isDisabled = false;
                screen.confirmButton.show();
                spirelink.util.Diag.info("choose_character: " + clazz.name() + " 已选中");
                return ActionResult.ok();
            }
        }
        return ActionResult.rejectParam(CODE_ILLEGAL, "没有这个角色: " + wanted);
    }

    private static ActionResult embark() {
        CharacterSelectScreen screen = charSelectScreen();
        if (screen == null || screen.confirmButton == null || screen.confirmButton.hb == null) {
            return ActionResult.reject(CODE_ILLEGAL, "启程按钮不在");
        }
        boolean selected = false;
        if (screen.options != null) {
            for (CharacterOption option : screen.options) {
                if (option != null && option.selected) {
                    selected = true;
                    break;
                }
            }
        }
        if (!selected) {
            return ActionResult.reject(CODE_ILLEGAL, "还没选角色，先 choose_character");
        }
        // Both: click the real button (vanilla path) and start the fade directly, so embark does not
        // depend on the hitbox state machine picking the click up while not hovered.
        screen.confirmButton.hb.clicked = true;
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu != null) {
            menu.isFadingOut = true;
            spirelink.util.Diag.info("embark: 选中 " + CardCrawlGame.chosenCharacter + "，开始淡出并建角色");
        }
        return ActionResult.ok();
    }

    /**
     * Sets the ascension level for the run that is about to start.
     *
     * <p>Vanilla keeps the chosen level on the character-select screen and only copies it into
     * {@code AbstractDungeon.ascensionLevel} inside that screen's own confirm handler
     * ({@code isAscensionMode ? ascensionLevel : 0}); the little left/right arrows just call
     * {@link CharacterOption#incrementAscensionLevel(int)}. So setting the two screen fields before
     * {@code embark} is exactly what a human clicking those arrows does.</p>
     */
    private static ActionResult chooseAscension(JsonObject payload) {
        CharacterSelectScreen screen = charSelectScreen();
        if (screen == null || screen.options == null) {
            return ActionResult.reject(CODE_ILLEGAL, "选人界面还没准备好");
        }
        CharacterOption selected = null;
        for (CharacterOption option : screen.options) {
            if (option != null && option.selected) {
                selected = option;
                break;
            }
        }
        if (selected == null) {
            return ActionResult.reject(CODE_ILLEGAL, "还没选角色，先 choose_character");
        }
        int wanted;
        try {
            // Issue #77: strict type. The old num() coerced the string "1" into 1 and accepted it.
            wanted = AscensionRequest.parse(payload).level;
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage());
        }
        int cap = ActionPlanner.ascensionCap(selected);
        if (wanted > cap) {
            // Better than silently clamping: the agent asked for a difficulty the save cannot offer.
            return ActionResult.rejectParam(CODE_ILLEGAL,
                    "这个角色只解锁到升天 " + cap + "（要的是 " + wanted + "）");
        }
        int target = wanted;
        // Deliberately NOT CharacterOption.incrementAscensionLevel(): it bails out when the level is
        // above its maxAscensionLevel field, and that field is only refreshed a frame after the
        // character is selected - so right after choose_character it still reads 1 and every level
        // above 1 would be silently dropped. Write the same three fields it would have written.
        selected.saveChosenAscensionLevel(target);
        screen.ascensionLevel = target;
        if (target >= 1) {
            // A_TEXT is indexed level-1 (vanilla does the same); 0 means "no ascension" and has no
            // info line, so it keeps whatever was there.
            try {
                screen.ascLevelInfoString = CharacterSelectScreen.A_TEXT[target - 1];
            } catch (RuntimeException ignored) {
                // Informational text only; the level itself is already set.
            }
        }
        screen.isAscensionMode = target > 0;
        if (screen.ascensionLevel != target) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "升天等级没设上：期望 " + target + "，屏幕上是 " + screen.ascensionLevel);
        }
        spirelink.util.Diag.info("choose_ascension: level=" + target + "（上限 " + cap
                + "，isAscensionMode=" + screen.isAscensionMode + "）");
        return ActionResult.ok();
    }

    /**
     * The pause-menu "Abandon Run", minus the confirm popup (an agent cannot misclick a dialog).
     *
     * <p>Mirrors {@code ConfirmPopup.yesButtonEffect()} for {@code ABANDON_MID_RUN}: fade the music,
     * clear the room's event, close whatever screen is up, then {@code CardCrawlGame.startOver()}.
     * The save is deleted as well, otherwise the main menu keeps offering "Continue" for a run the
     * player already walked away from.</p>
     */
    private static ActionResult abandonMidRun() {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在不在对局里，没有可放弃的局");
        }
        try {
            int act = AbstractDungeon.actNum;
            int floor = AbstractDungeon.floorNum;
            CardCrawlGame.music.fadeAll();
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null) {
                room.clearEvent();
            }
            AbstractDungeon.closeCurrentScreen();
            final String savePath = SaveAndContinue.getPlayerSavePath(player.chosenClass);
            SaveAndContinue.deleteSave(player);
            CardCrawlGame.startOver();
            // startOver() only sets a flag; the dungeon writes one last autosave during the fade and
            // that resurrected the file (and the "continue" button with it). Sweep once more after it
            // is really gone.
            try {
                com.badlogic.gdx.utils.Timer.schedule(new com.badlogic.gdx.utils.Timer.Task() {
                    @Override
                    public void run() {
                        try {
                            com.badlogic.gdx.Gdx.files.local(savePath).delete();
                            spirelink.util.Diag.info("abandon_mid_run: 二次清理存档 " + savePath);
                        } catch (Throwable ignored) {
                            // best effort
                        }
                    }
                }, 2.0f);
            } catch (Throwable ignored) {
                // Timer is a nicety, not a requirement.
            }
            spirelink.util.Diag.info("abandon_mid_run: 已放弃本局 act=" + act + " floor=" + floor
                    + "，存档已删");
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("abandon_mid_run", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "放弃失败: " + t);
        }
    }

    private static ActionResult back() {
        CharacterSelectScreen screen = charSelectScreen();
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null) {
            return ActionResult.reject(CODE_ILLEGAL, "主菜单还没准备好");
        }
        if (menu.screen == MainMenuScreen.CurScreen.CHAR_SELECT
                && screen != null && screen.cancelButton != null && screen.cancelButton.hb != null) {
            screen.cancelButton.hb.clicked = true;
            return ActionResult.ok();
        }
        if (menu.panelScreen != null && menu.panelScreen.button != null
                && menu.panelScreen.button.hb != null) {
            menu.panelScreen.button.hb.clicked = true;
            return ActionResult.ok();
        }
        return ActionResult.reject(CODE_ILLEGAL, "当前界面没有返回按钮");
    }


    // ---------------------------------------------------------------- combat

    /**
     * {@code play_card}: validate against the live hand, then push a {@code CardQueueItem} — the
     * same path a mouse click takes, so the card resolves through the normal action queue.
     */
    private static ActionResult playCard(JsonObject payload) {
        CardPlayRequest request;
        try {
            request = CardPlayRequest.parse(payload);
        } catch (BadRequestException e) {
            return ActionResult.reject(e.code, e.getMessage());
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || player.hand == null || player.hand.group == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有手牌");
        }
        if (handSelectOpen()) {
            return ActionResult.reject(ErrorCodes.HAND_SELECT_OPEN, "有选牌界面开着，先处理它");
        }
        if (!playerTurn()) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "不是玩家回合或动画没结束");
        }
        AbstractCard card = findInHand(player, request.cardUuid);
        if (card == null) {
            return ActionResult.rejectParam(ErrorCodes.CARD_NOT_IN_HAND, "手牌里没有这张牌: "
                    + request.cardUuid);
        }
        AbstractMonster target = null;
        boolean needsTarget = card.target == AbstractCard.CardTarget.ENEMY
                || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
        if (needsTarget) {
            if (!request.hasTarget()) {
                // Same shape as use_potion (#104): name the legal targets instead of just "missing".
                return ActionResult.rejectParam(ErrorCodes.TARGET_REQUIRED,
                        "这张牌需要 target_index（可选目标: " + ActionPlanner.targetChoices() + "）");
            }
            target = monsterAt(request.targetIndex);
            if (target == null) {
                return ActionResult.rejectParam(ErrorCodes.TARGET_INVALID,
                        "target_index 越界或目标已死: " + request.targetIndex);
            }
            if (player.hasPower("Surrounded")) {
                player.flipHorizontal = target.drawX < player.drawX;
            }
        }
        if (!card.canUse(player, target)) {
            return ActionResult.reject(EnergyPanel.getCurrentEnergy() < card.cost && card.cost >= 0
                    ? ErrorCodes.NOT_ENOUGH_ENERGY : ErrorCodes.CARD_NOT_PLAYABLE,
                    "当前打不出这张牌（能量/条件不满足）");
        }
        AbstractDungeon.actionManager.cardQueue.add(new CardQueueItem(card, target));
        spirelink.util.Diag.info("play_card: " + card.cardID + " uuid=" + card.uuid
                + " target=" + (target == null ? "无" : target.id));
        return ActionResult.ok();
    }

    /** {@code end_turn} mirrors the vanilla button: {@code disable(true)} ends the player turn. */
    private static ActionResult endTurn() {
        if (!playerTurn()) {
            // Not a waiting condition but a wrong-action one: recovery.md tells the agent to "wait"
            // on NOT_READY, which is the wrong advice for "this action does not apply here".
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    "现在不是你的回合/不在战斗里，不能结束回合");
        }
        if (!ActionPlanner.endTurnAvailable()) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "结束回合按钮当前不可用（不是玩家回合/动画中）");
        }
        AbstractDungeon.overlayMenu.endTurnButton.disable(true);
        spirelink.util.Diag.info("end_turn: 已请求结束回合");
        return ActionResult.ok();
    }

    private static ActionResult potion(JsonObject payload, boolean use) {
        PotionRequest request;
        try {
            request = PotionRequest.parse(payload);
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage());
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || player.potions == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "还没有药水系统");
        }
        if (request.slot >= player.potions.size()) {
            return ActionResult.rejectParam(ErrorCodes.POTION_SLOT_EMPTY,
                    "槽位越界: " + request.slot);
        }
        AbstractPotion potion = player.potions.get(request.slot);
        if (spirelink.state.PotionSerializer.isPlaceholder(potion)) {
            return ActionResult.rejectParam(ErrorCodes.POTION_SLOT_EMPTY, "这个槽位没有药水");
        }
        // Keep the object's own index in sync: use/discard end in TopPanel.destroyPotion(potion.slot),
        // which throws ArrayIndexOutOfBoundsException(-1) for potions created outside the normal flow.
        if (potion.slot != request.slot) {
            spirelink.util.Diag.info("药水槽位修正: " + potion.ID + " slot " + potion.slot
                    + "->" + request.slot);
            potion.slot = request.slot;
        }
        potion.isObtained = true;
        if (use && !potion.canUse()) {
            return ActionResult.reject(ErrorCodes.POTION_UNUSABLE,
                    potion.name + " 现在用不了（canUse=false：战斗中需要轮到自己且目标合法）");
        }
        if (!use && !potion.canDiscard()) {
            return ActionResult.reject(ErrorCodes.POTION_UNDISCARDABLE,
                    potion.name + " 现在不能丢弃（canDiscard=false：战斗中只有自己回合才能丢药水）");
        }
        if (use) {
            if (!playerTurn()) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "不是玩家回合");
            }
            AbstractMonster target = null;
            if (request.hasTarget()) {
                // Validate the index before touching the game: most potions index monsters directly
                // and an out-of-range value surfaces as ArrayIndexOutOfBoundsException: -1.
                List<AbstractMonster> alive = AbstractDungeon.getCurrRoom() == null
                        || AbstractDungeon.getCurrRoom().monsters == null
                        ? java.util.Collections.<AbstractMonster>emptyList()
                        : AbstractDungeon.getCurrRoom().monsters.monsters;
                if (request.targetIndex < 0 || request.targetIndex >= alive.size()) {
                    return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                            "target_index 越界: " + request.targetIndex + "（可选 0.."
                                    + (alive.size() - 1) + "）");
                }
                target = monsterAt(request.targetIndex);
                if (target == null || target.isDeadOrEscaped()) {
                    return ActionResult.rejectParam(ErrorCodes.TARGET_INVALID,
                            "target_index 目标已死或不可选: " + request.targetIndex);
                }
            } else if (potion.targetRequired) {
                // Issue #104: a target-required potion used to hit the first alive monster when
                // target_index was missing - the caller never learned which monster it hit, and the
                // planner never advertises that shape (it lists one use_potion per target). Ask for
                // the target and list the legal ones, like play_card does.
                return ActionResult.rejectParam(ErrorCodes.TARGET_REQUIRED,
                        "这瓶药水需要 payload.target_index（可选目标: "
                                + ActionPlanner.targetChoices() + "）");
            } else if (potion.isThrown) {
                // Thrown but not target-required (the animation wants a monster, the effect does not):
                // keep the first-alive fallback so the throw never runs without a target.
                target = firstAliveMonster();
                if (target == null) {
                    return ActionResult.reject(ErrorCodes.NOT_READY, "没有可选目标");
                }
            }
            try {
                potion.use(target == null ? player : target);
            } catch (Throwable t) {
                spirelink.util.Diag.warn("use_potion 抛异常: " + potion.ID + " target="
                        + (target == null ? "player" : target.name) + " " + t + " | "
                        + firstFrames(t, 8));
                throw t;
            }
            if (player.relics != null) {
                for (AbstractRelic relic : player.relics) {
                    relic.onUsePotion();
                }
            }
            spirelink.util.Diag.info("use_potion: " + potion.ID + " slot=" + request.slot);
        } else {
            potion.discarded = true;
            spirelink.util.Diag.info("discard_potion: " + potion.ID + " slot=" + request.slot);
        }
        AbstractDungeon.topPanel.destroyPotion(potion.slot);
        // Verify the slot really emptied; otherwise the caller keeps seeing a potion it cannot remove.
        if (request.slot < player.potions.size()
                && !spirelink.state.PotionSerializer.isPlaceholder(player.potions.get(request.slot))) {
            spirelink.util.Diag.warn("destroyPotion(" + potion.slot + ") 之后槽位仍有药水，强制置空");
            player.potions.set(request.slot,
                    new com.megacrit.cardcrawl.potions.PotionSlot(request.slot));
        }
        return ActionResult.ok();
    }

    private static ActionResult chooseHandCards(JsonObject payload) {
        HandSelectRequest request;
        try {
            request = HandSelectRequest.parse(payload);
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage());
        }
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        AbstractPlayer player = AbstractDungeon.player;
        if (screen == null || player == null || player.hand == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌界面");
        }
        // Issue #102: validate the WHOLE request against the same pure policy the planner advertises
        // from, and only then touch the screen - a rejected request must not leave half of its cards
        // selected (the old loop returned mid-way).
        int already = handSelectedCount(screen);
        int wanted = 0;
        for (String uuid : request.cardUuids) {
            AbstractCard card = findInHand(player, uuid);
            if (card == null) {
                return ActionResult.rejectParam(ErrorCodes.CARD_NOT_IN_HAND, "手牌里没有 " + uuid);
            }
            if (!handCardSelected(screen, card)) {
                wanted++;
            }
        }
        if (wanted > 0 && !HandSelectPolicy.canSelect(screen.numCardsToSelect, already, wanted)) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    HandSelectPolicy.selectTooManyMessage(screen.numCardsToSelect, already, wanted));
        }
        for (String uuid : request.cardUuids) {
            AbstractCard card = findInHand(player, uuid);
            if (card == null || handCardSelected(screen, card)) {
                continue;
            }
            selectHandCard(screen, card);
        }
        spirelink.util.Diag.info("choose_hand_cards: 新选 " + wanted + " 张（之前已有 "
                + already + " 张，共要 " + screen.numCardsToSelect + " 张）");
        return ActionResult.ok();
    }

    /** How many cards the hand-select screen holds right now. */
    private static int handSelectedCount(HandCardSelectScreen screen) {
        return screen == null || screen.selectedCards == null ? 0 : screen.selectedCards.size();
    }

    private static boolean handCardSelected(HandCardSelectScreen screen, AbstractCard card) {
        return screen != null && screen.selectedCards != null && screen.selectedCards.contains(card);
    }

    private static ActionResult confirmHandSelect() {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        if (screen == null || screen.button == null || screen.button.hb == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌界面");
        }
        int selected = handSelectedCount(screen);
        if (!HandSelectPolicy.canConfirm(screen.numCardsToSelect, selected, screen.canPickZero,
                ActionPlanner.anyNumber(screen), screen.upTo)) {
            // Issue #102: the planner does not advertise confirm here, and the click is a no-op in
            // vanilla (HandCardSelectScreen.update only closes on an accepted count). Answering ok
            // is what produced the reported 10.6 s queued + 13 s lock: the state could not change.
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    HandSelectPolicy.confirmRefusedMessage(screen.numCardsToSelect, selected));
        }
        screen.button.hb.clicked = true;
        return ActionResult.ok();
    }

    private static ActionResult cancelHandSelect() {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        if (screen == null || screen.button == null || screen.button.hb == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌界面");
        }
        int selected = handSelectedCount(screen);
        if (!HandSelectPolicy.canCancel(screen.numCardsToSelect, selected, screen.canPickZero,
                ActionPlanner.anyNumber(screen), screen.upTo)) {
            // B2-14 + #102: cancel means "click with nothing selected"; with cards picked the same
            // click would apply them, and on a mandatory screen vanilla has no cancel at all.
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    HandSelectPolicy.cancelRefusedMessage(screen.numCardsToSelect, selected,
                            screen.canPickZero));
        }
        screen.button.hb.clicked = true;
        return ActionResult.ok();
    }

    /** Calls the screen's own private {@code selectHoveredCard()} (no mouse involved). */
    private static void selectHandCard(HandCardSelectScreen screen, AbstractCard card) {
        screen.hoveredCard = card;
        card.setAngle(0.0f, false);
        try {
            java.lang.reflect.Method method = HandCardSelectScreen.class
                    .getDeclaredMethod("selectHoveredCard");
            method.setAccessible(true);
            method.invoke(screen);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("selectHoveredCard 反射失败: " + t);
            if (screen.selectedCards != null) {
                screen.selectedCards.addToTop(card);
                screen.numSelected++;
            }
        }
    }

    private static boolean handSelectOpen() {
        return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT;
    }

    /**
     * True when a card-select grid owns the screen (issues #83 / #64).
     *
     * <p>Vanilla's "transform a card" picker is the very same {@code GridCardSelectScreen}
     * ({@code forTransform = true}), and the screen is reported as {@code TRANSFORM} rather than
     * {@code GRID} on some paths. Treating both as "a grid is up" is what lets
     * {@code choose_grid_cards} / {@code confirm_grid} / {@code cancel_grid} finish the transform
     * instead of being rejected with "选牌网格没开着".</p>
     */
    static boolean cardSelectScreenUp() {
        AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
        return screen == AbstractDungeon.CurrentScreen.GRID
                || screen == AbstractDungeon.CurrentScreen.TRANSFORM;
    }

    private static boolean playerTurn() {
        return ActionPlanner.playerTurnNow();
    }

    private static AbstractCard findInHand(AbstractPlayer player, String uuid) {
        for (AbstractCard card : player.hand.group) {
            if (card != null && card.uuid != null && card.uuid.toString().equals(uuid)) {
                return card;
            }
        }
        return null;
    }

    /** @return the monster at the raw slot index used by {@code get_state.combat.monsters[].index} */
    private static AbstractMonster monsterAt(Integer index) {
        if (index == null) {
            return null;
        }
        MonsterGroup group = AbstractDungeon.getMonsters();
        if (group == null || group.monsters == null || index < 0 || index >= group.monsters.size()) {
            return null;
        }
        AbstractMonster monster = group.monsters.get(index);
        if (monster == null || monster.isDeadOrEscaped() || monster.currentHealth <= 0) {
            return null;
        }
        return monster;
    }

    // ---------------------------------------------------------------- abandon popup

    private static ActionResult abandonPopup(boolean confirm) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (!ActionPlanner.abandonPopupShown(menu)) {
            return ActionResult.reject(CODE_ILLEGAL, "当前没有放弃存档的确认框");
        }
        Hitbox hitbox = confirm ? menu.abandonPopup.yesHb : menu.abandonPopup.noHb;
        if (hitbox == null) {
            return ActionResult.reject(CODE_ILLEGAL, "确认框按钮不可用");
        }
        hitbox.clicked = true;
        return ActionResult.ok();
    }

    // ---------------------------------------------------------------- tutorial tip

    /** What {@code dismiss_tip} decided to do; see {@link #decideDismiss(boolean, boolean)}. */
    public enum DismissStep {
        /** A real FTUE tip is up: click its GotItButton (a multi-page tip advances one page). */
        GOT_IT,
        /** No tip, but a screen is open: close it with the game's own closeCurrentScreen(). */
        CLOSE_SCREEN,
        /** Nothing to dismiss: refuse rather than answer ok while nothing changed. */
        REJECT
    }

    /**
     * Chooses the dismiss strategy without touching the game, so the #50 cases stay unit-testable.
     *
     * <p>#50: the four pile/deck views ({@code MASTER_DECK_VIEW} / {@code DISCARD_VIEW} /
     * {@code EXHAUST_VIEW} / {@code GAME_DECK_VIEW}) reuse the {@code ui=tutorial_tip} slot in
     * {@code UiNames}, but they are not FTUE tips. The old code rejected every {@code dismiss_tip}
     * because {@code AbstractDungeon.ftue == null}, and since that was the only action the agent was
     * allowed to send, a human opening the deck view locked the agent out. Those screens are closed
     * by {@code AbstractDungeon.closeCurrentScreen()} — exactly what the top bar / pile buttons do
     * themselves when the human clicks them a second time.
     *
     * @param ftueUp   {@code AbstractDungeon.ftue != null}
     * @param screenUp {@code AbstractDungeon.screen} is neither null nor {@code NONE}
     */
    public static DismissStep decideDismiss(boolean ftueUp, boolean screenUp) {
        if (ftueUp) {
            return DismissStep.GOT_IT;
        }
        return screenUp ? DismissStep.CLOSE_SCREEN : DismissStep.REJECT;
    }

    // ------------------------------------------------------------ where a close lands (#51)

    /**
     * Where closing a view screen must leave the player (#51).
     *
     * <p>Vanilla restores {@code AbstractDungeon.previousScreen}, but that field is only written when
     * the view was opened from a screen vanilla cares about. Opening the deck view from a
     * <i>dismissable</i> map makes {@code TopPanel#updateDeckViewButtonLogic} close the map first and
     * leaves {@code previousScreen} at whatever it held before (it was {@code null} in the reported
     * run), so the close restored nothing and the agent read {@code ui=neow} from the finished
     * NeowRoom behind the map.
     */
    public enum CloseTarget {
        /** The dungeon map is the screen the player really is on: land on it ({@code ui=map}). */
        MAP,
        /** No screen belongs to this context: closing reveals the room (combat / live Neow dialog). */
        NONE,
        /** A real decision screen was underneath: let vanilla restore {@code previousScreen}. */
        RESTORE
    }

    /** The four pile/deck views (#50); these are the screens {@code dismiss_tip} closes. */
    private static final Set<String> VIEW_SCREENS = nameSet(
            "MASTER_DECK_VIEW", "DISCARD_VIEW", "EXHAUST_VIEW", "GAME_DECK_VIEW");

    /**
     * Screens a view may be opened from and be restored to: vanilla writes every one of these into
     * {@code previousScreen} when the human opens a view on top of them, so a restore is trustworthy.
     */
    private static final Set<String> RESTORABLE_SCREENS = nameSet(
            "COMBAT_REWARD", "CARD_REWARD", "BOSS_REWARD", "GRID", "HAND_SELECT", "SHOP",
            "CHOOSE_ONE", "TRANSFORM", "SETTINGS", "INPUT_SETTINGS", "DEATH", "VICTORY",
            "DOOR_UNLOCK", "CREDITS");

    /**
     * Values {@code previousScreen} must never send the player back to: another view (vanilla's own
     * close path clears those too), a finished unlock/neow notice, or "no screen at all".
     */
    private static final Set<String> NON_LANDING_SCREENS = nameSet(
            "NONE", "FTUE", "NEOW_UNLOCK", "UNLOCK", "NO_INTERACT");

    /**
     * Screens that can really be underneath a view in the middle of a fight: the card-selection
     * screens. A reward screen there is a leftover from an earlier room (rewards only appear once the
     * room phase is {@code COMPLETE}), so restoring it would fake a decision the player does not have.
     */
    private static final Set<String> COMBAT_SCREENS = nameSet("HAND_SELECT", "GRID", "CHOOSE_ONE");

    private static Set<String> nameSet(String... names) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));
    }

    /**
     * Chooses where {@code dismiss_tip} should land, without touching the game (#51).
     *
     * <p>Ordering matters. The map wins first, because it is the only signal that survives a view
     * opened from a dismissable map (vanilla leaves {@code previousScreen} stale there). A real
     * decision screen wins over the room next, because the reward/grid is what the player was
     * deciding on. Only then does the room decide — combat and a live event dialog are room state,
     * not screens, so a close must not leave a screen behind for them.
     *
     * <p>{@code mapUnderneath} is only a partial signal: when the human opens a view from a
     * <i>dismissable</i> map, vanilla closes the map first, so nothing is drawn underneath and the
     * field is false even though the map is where the player belongs. That case is caught after the
     * close by {@link #selectFallbackTarget}.
     *
     * @param previousScreen {@code AbstractDungeon.previousScreen} name, {@code null} when unset
     * @param roomPhase      {@code AbstractDungeon.getCurrRoom().phase} name, {@code null} when unknown
     * @param mapUnderneath  the dungeon map is really the screen behind the view
     *                       ({@code DungeonMap.targetAlpha > 0}, i.e. vanilla did not close it)
     * @param screenUp       a screen is up at all (the caller already decided there is one)
     */
    public static CloseTarget selectCloseTarget(String previousScreen, String roomPhase,
                                                boolean mapUnderneath, boolean screenUp) {
        if (!screenUp) {
            // Nothing is open: closing must not invent a screen to show.
            return CloseTarget.NONE;
        }
        String previous = previousScreen == null
                ? "NONE" : previousScreen.toUpperCase(Locale.ROOT);
        if (mapUnderneath || "MAP".equals(previous)) {
            // 地图语境：关掉视图后要回到地图，而不是上一屏。
            return CloseTarget.MAP;
        }
        if (NON_LANDING_SCREENS.contains(previous) || VIEW_SCREENS.contains(previous)) {
            // 视图屏本身 / 已结束的解锁提示：没有可恢复的界面，交给房间语境。
            return CloseTarget.NONE;
        }
        String phase = roomPhase == null ? "" : roomPhase.toUpperCase(Locale.ROOT);
        if ("COMBAT".equals(phase)) {
            // 战斗中：只有选牌/网格屏可能真的压在下面；奖励屏是上一间房的残留，不能恢复。
            return COMBAT_SCREENS.contains(previous) ? CloseTarget.RESTORE : CloseTarget.NONE;
        }
        if (RESTORABLE_SCREENS.contains(previous)) {
            return CloseTarget.RESTORE;
        }
        if ("EVENT".equals(phase)) {
            // 真 Neow/事件：对话框是房间级的，屏就该是 NONE（ui 由房间决定）。
            return CloseTarget.NONE;
        }
        return CloseTarget.RESTORE;
    }

    /**
     * Rooms whose finished state still has something to do in them, so a close must never jump to the
     * map: the act boss (proceed leads to the treasure room and its relic), the treasure rooms (the
     * chest), victory, and the shop (the merchant is still there after a purge — {@code ui=shop_room}
     * is a real state with {@code screen=NONE} and {@code room_phase=COMPLETE}).
     */
    private static final Set<String> ROOMS_WITH_SOMETHING_LEFT = nameSet(
            "MonsterRoomBoss", "TreasureRoomBoss", "TreasureRoom", "VictoryRoom", "TrueVictoryRoom",
            "ShopRoom");

    /**
     * Where a close that landed on {@code screen=NONE} should really leave the player (#51).
     *
     * <p>Why this exists at all: when the human opens a view from a <i>dismissable</i> map, vanilla
     * closes the map first ({@code TopPanel#updateDeckViewButtonLogic} → {@code closeCurrentScreen()}
     * → {@code DungeonMapScreen.close()}), and it never writes {@code previousScreen}. So after the
     * view is dismissed there is nothing to restore — the close lands on {@code screen=NONE} while the
     * player is standing on the map with a node still to pick, and {@code deriveUi()} answers from the
     * room behind ({@code neow} for a finished NeowRoom). The room is the only thing left to read.
     *
     * <p>Combat never gets the map: mid-fight {@code screen=NONE} <i>is</i> the correct HUD.
     *
     * @param roomType         {@code AbstractDungeon.getCurrRoom()} simple class name
     * @param roomPhase        current room phase name
     * @param bossChestPending {@link BossChestGate#mustOpenChest()} — the act-boss relic is unclaimed
     */
    public static CloseTarget selectFallbackTarget(String roomType, String roomPhase,
                                                   boolean bossChestPending) {
        String phase = roomPhase == null ? "" : roomPhase.toUpperCase(Locale.ROOT);
        if ("COMBAT".equals(phase)) {
            // 战斗语境：战斗 HUD 就是 screen=NONE，绝不能把地图开出来。
            return CloseTarget.NONE;
        }
        if (roomType == null || roomType.isEmpty()) {
            // Not in a run: there is no map to go back to.
            return CloseTarget.NONE;
        }
        if (ROOMS_WITH_SOMETHING_LEFT.contains(roomType) || bossChestPending) {
            // Opening the map here would walk past a chest / a boss relic / the merchant.
            return CloseTarget.NONE;
        }
        // A finished room's next step is the map (this is exactly what proceed would do), and so is
        // the NeowRoom the blessing ended in — the reported #51 case.
        return "COMPLETE".equals(phase) ? CloseTarget.MAP : CloseTarget.NONE;
    }

    /** True for the four pile/deck views {@code dismiss_tip} closes (#50). */
    static boolean isViewScreen(AbstractDungeon.CurrentScreen screen) {
        return screen != null && VIEW_SCREENS.contains(screen.name());
    }

    private static String nameOf(AbstractDungeon.CurrentScreen screen) {
        return screen == null ? null : screen.name();
    }

    /** Current room class simple name; {@code null} outside a run. */
    private static String roomTypeName() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null ? null : room.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Current room phase name; {@code null} outside a run. */
    private static String roomPhaseName() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null || room.phase == null ? null : room.phase.name();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * True when the dungeon map is the screen the player is really on.
     *
     * <p>The {@code DungeonMapScreen} object exists from the start of the run, so its presence says
     * nothing. {@code DungeonMap.targetAlpha} is what {@code show()} / {@code hide()} drive, i.e. the
     * only field that says "the map is drawn behind whatever view is open right now".
     */
    private static boolean mapScreenUnderneath() {
        try {
            return AbstractDungeon.dungeonMapScreen != null
                    && AbstractDungeon.dungeonMapScreen.map != null
                    && AbstractDungeon.dungeonMapScreen.map.targetAlpha > 0.5f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ActionResult dismissTip() {
        // Issue #98: while a deck peek is up, closing it IS the dismissal. Without this the peek was
        // force-closed first and the generic path below then closed the screen underneath (the grid
        // the agent was working on).
        if (spirelink.ui.DeckPeek.active()) {
            spirelink.ui.DeckPeek.closeForced("dismiss_tip");
            spirelink.util.Diag.info("dismiss_tip: 关掉了牌表 peek（ui=" + AbstractDungeon.screen + "）");
            return ActionResult.verified();
        }
        FtueTip tip = AbstractDungeon.ftue;
        AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
        boolean screenUp = screen != null && screen != AbstractDungeon.CurrentScreen.NONE;
        switch (decideDismiss(tip != null, screenUp)) {
            case GOT_IT:
                return clickGotIt(tip);
            case CLOSE_SCREEN:
                return closeTipScreen(screen);
            default:
                return ActionResult.reject(CODE_ILLEGAL, "当前没有教程弹窗，也没有可关闭的界面");
        }
    }

    /**
     * Issue #93: vanilla turns the *first* relic pickup into a RELIC_TIP FtueTip
     * ({@code AbstractRelic.obtain()} -> {@code relicTip()} assigns {@code AbstractDungeon.ftue}),
     * which lands on top of the screen the claim was using. Left up it reads as
     * {@code ui=tutorial_tip} and the agent has to know to dismiss it — QA lost a relic that way.
     *
     * <p>Only the tip's own "got it" button is clicked, never a generic
     * {@code closeCurrentScreen()}: a relic whose effect opens a grid has that grid underneath, and
     * closing it would throw the pick away.</p>
     */
    static void dismissFollowUpTip(String where) {
        try {
            FtueTip tip = AbstractDungeon.ftue;
            if (tip == null) {
                return;
            }
            GotItButton button = ReflectionHacks.getPrivate(tip, FtueTip.class, "button");
            if (button != null && button.hb != null) {
                button.hb.clicked = true;
                spirelink.util.Diag.info("claim_relic: 已收掉跟随弹出的教程提示（" + where
                        + "），ui 不会停在 tutorial_tip");
                return;
            }
            spirelink.util.Diag.warn("claim_relic: 有教程提示（" + where
                    + "）但读不到 got-it 按钮，留给 dismiss_tip");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("claim_relic: 收教程提示失败（" + where + "）: " + t);
        }
    }

    /** The FTUE tip's own "got it" button; each click advances a multi-page tip. */
    private static ActionResult clickGotIt(FtueTip tip) {
        try {
            GotItButton button = ReflectionHacks.getPrivate(tip, FtueTip.class, "button");
            if (button != null && button.hb != null) {
                button.hb.clicked = true;
                return ActionResult.ok();
            }
        } catch (Throwable ignored) {
            // fall through to the generic path
        }
        AbstractDungeon.closeCurrentScreen();
        return ActionResult.ok();
    }

    /**
     * {@code dismiss_tip} for a screen that is not an FTUE tip (#50).
     *
     * <p>Uses the very call vanilla uses when the human clicks the top bar / pile button again, then
     * verifies the screen really went away: answering ok while a view stayed open is what left the
     * agent waiting forever.
     *
     * <p>#51: for the four pile/deck views the restore target is normalised first
     * ({@link #selectCloseTarget}). {@code previousScreen} is stale when the view was opened from a
     * dismissable map, so closing used to drop the agent into a Neow dialog that was already over
     * while it had been standing on the map.
     */
    private static ActionResult closeTipScreen(AbstractDungeon.CurrentScreen screen) {
        try {
            String previous = nameOf(AbstractDungeon.previousScreen);
            boolean viewScreen = isViewScreen(screen);
            CloseTarget target = viewScreen
                    ? selectCloseTarget(previous, roomPhaseName(), mapScreenUnderneath(), true)
                    : CloseTarget.RESTORE;
            switch (target) {
                case MAP:
                    // Land on the map: it is the screen the player was really on.
                    AbstractDungeon.previousScreen = AbstractDungeon.CurrentScreen.MAP;
                    break;
                case NONE:
                    // No screen belongs to this context, so closing must reveal the room itself.
                    AbstractDungeon.previousScreen = null;
                    break;
                default:
                    // Vanilla clears a previous screen that points at the one being closed; leaving it
                    // would make closeCurrentScreen() reopen the same view instead of closing it.
                    if (AbstractDungeon.previousScreen == screen) {
                        AbstractDungeon.previousScreen = null;
                    }
                    break;
            }
            if (viewScreen) {
                // Same reset vanilla's own deck-button close does: a half-finished swap would
                // otherwise leave the screen untouched and the check below would reject a close
                // that actually worked.
                AbstractDungeon.screenSwap = false;
            }
            AbstractDungeon.closeCurrentScreen();
            boolean closed = AbstractDungeon.screen != screen;
            // What closeCurrentScreen() landed on, before the #51 settle step is allowed to correct it.
            String landed = nameOf(AbstractDungeon.screen);
            if (closed && viewScreen) {
                settleViewClose(target);
            }
            spirelink.util.Diag.info("dismiss_tip: ftue=null，closeCurrentScreen() 关闭 " + screen
                    + " previous=" + previous + " target=" + target
                    + " closed=" + closed + " screen=" + landed
                    + " final=" + nameOf(AbstractDungeon.screen));
            if (!closed) {
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "界面没关掉（screen=" + screen + "），稍后重试");
            }
            return ActionResult.verified();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("dismiss_tip", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "关闭当前界面失败: " + t);
        }
    }

    /**
     * Makes a closed view leave the player where they really are (#51, second round).
     *
     * <p>Two things can still be wrong after {@code closeCurrentScreen()} even though the screen did
     * change: the restore target can be a screen that is no longer live ({@code target=MAP} while
     * vanilla already hid the map), or the close lands on {@code screen=NONE} because there was
     * nothing to restore at all. Both are settled here, on the actual {@code AbstractDungeon.screen}.
     */
    private static void settleViewClose(CloseTarget target) {
        try {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                return;
            }
            if (target == CloseTarget.MAP) {
                if (!openMapScreen()) {
                    spirelink.util.Diag.warn("dismiss_tip: 关屏后应落到地图但没能打开（screen="
                            + AbstractDungeon.screen + "）");
                }
                return;
            }
            if (AbstractDungeon.screen != null
                    && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE) {
                // Landed on a real screen (a reward, a shop, ...): that is the answer, leave it alone.
                return;
            }
            CloseTarget fallback = selectFallbackTarget(
                    roomTypeName(), roomPhaseName(), BossChestGate.mustOpenChest());
            if (fallback == CloseTarget.MAP && !openMapScreen()) {
                spirelink.util.Diag.warn("dismiss_tip: 关屏后无人认领界面，想回到地图但打开失败（screen="
                        + AbstractDungeon.screen + "）");
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("dismiss_tip.settle", t);
        }
    }

    // ---------------------------------------------------------------- neow / event dialog

    private static ActionResult chooseNeowReward(JsonObject payload) {
        List<LargeDialogOptionButton> options = ActionPlanner.dialogOptions();
        if (options == null) {
            return ActionResult.reject(CODE_ILLEGAL, "当前没有事件对话框");
        }
        int index = num(payload, "index", -1);
        if (index < 0 || index >= options.size()) {
            return ActionResult.rejectParam(CODE_ILLEGAL, "选项下标越界: " + index);
        }
        LargeDialogOptionButton option = options.get(index);
        if (option == null || option.isDisabled || option.hb == null) {
            return ActionResult.rejectParam(CODE_ILLEGAL, "该选项不可选: " + index);
        }
        // NeowEvent also implements buttonEffect(int), so the same direct call works here.
        String failure = invokeButtonEffect(index);
        if (failure != null) {
            spirelink.util.Diag.warn("neow: buttonEffect(" + index + ") 失败（" + failure
                    + "），退回模拟点击");
            spirelink.patches.DialogClickPatch.request(option);
        }
        return ActionResult.ok();
    }

    // ---------------------------------------------------------------- screens (P2.5)

    /**
     * {@code take_card}: goes through the screen's own private {@code acquireCard(AbstractCard)}.
     *
     * <p>#111: the index used to be read with {@code num()} — i.e. {@code getAsInt()} — so
     * {@code take_card{"index":1.5}} was <b>silently truncated</b> to 1 and took a card the caller
     * never named, while the very same value on {@code take_reward} was a strict BAD_REQUEST. The
     * index is now {@link IntArgument}'s (strict integer, non-negative) and a rejected index carries
     * the legal range plus the cards that can really be named, exactly like {@code take_reward}
     * (C7-04 / C8-02).</p>
     */
    private static ActionResult takeCard(JsonObject payload) {
        CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
        // #111: the payload is answered first - a malformed index is the caller's mistake no matter
        // what the screen is doing, and it must never be silently rounded into a different card.
        Integer requested;
        try {
            requested = IntArgument.optionalNonNegative(payload, "index");
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage() + "；"
                    + CardRewardPolicy.rangeText(screen == null || screen.rewardGroup == null
                            ? 0 : screen.rewardGroup.size()) + "；"
                    + cardRewardSummary(screen));
        }
        if (screen == null || screen.rewardGroup == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌界面");
        }
        int index = requested == null ? -1 : requested.intValue();
        // uuid 比 index 更具体：给了 uuid 就按 uuid 找牌（找不到直接拒），别默默按 index 拿另一张。
        // 试玩 agent 实测「uuid 乱填也能选成功」——拿着上一屏的 uuid 会悄悄选中别的牌（issue #16）。
        String uuid = str(payload, "uuid", null);
        AbstractCard card = null;
        if (uuid != null && !uuid.isEmpty()) {
            for (AbstractCard candidate : screen.rewardGroup) {
                // AbstractCard.uuid is a java.util.UUID: String.equals(UUID) is always false, which
                // made every take_card that carried a uuid fail as "uuid 不在这几张牌里".
                if (candidate != null && uuid.equals(String.valueOf(candidate.uuid))) {
                    card = candidate;
                    break;
                }
            }
            if (card == null) {
                // 把可选 uuid 一起报出来：agent 经常拿着上一屏的 uuid 过来，光说「不在这几张里」
                // 它只能靠再读一次 state 猜（试玩 agent 报过 take_card 带 uuid 就被拒）。
                StringBuilder avail = new StringBuilder();
                for (AbstractCard candidate : screen.rewardGroup) {
                    if (candidate != null) {
                        if (avail.length() > 0) {
                            avail.append(", ");
                        }
                        avail.append(candidate.uuid).append('(').append(candidate.name).append(')');
                    }
                }
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        "uuid 不在这几张牌里: " + uuid + "；" + cardRewardSummary(screen)
                                + "；可选 uuid: " + avail);
            }
            index = screen.rewardGroup.indexOf(card);
        } else {
            if (index < 0) {
                // Neither uuid nor index: name the payload this action expects (C7-04 shape).
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        "缺少 index（payload 要写 {\"index\": <0 起的牌序号>} 或"
                                + " {\"uuid\": \"<卡的 uuid>\"}）："
                                + CardRewardPolicy.rangeText(screen.rewardGroup.size()) + "；"
                                + cardRewardSummary(screen));
            }
            if (index >= screen.rewardGroup.size()) {
                // C7-04 / C8-02: same shape take_reward answers with (#101) - the valid range plus
                // the cards that can really be named, instead of a bare "牌序号越界: 9".
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        "payload.index=" + index + " 越界："
                                + CardRewardPolicy.rangeText(screen.rewardGroup.size()) + "；"
                                + cardRewardSummary(screen));
            }
            card = screen.rewardGroup.get(index);
        }
        if (card == null) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    "这张牌不存在；" + cardRewardSummary(screen));
        }
        try {
            // 原版点击一次选牌走的是这个方法里的四选一分支（javap 核对过 CardRewardScreen.update）：
            //   chooseOne → card.onChoseThisOption()
            //   codex     → codexCard = card
            //   discovery → discoveryCard = card        ← 【发现】/药水走的这条
            //   其余      → acquireCard(card)           ← 普通奖励走的这条
            // 然后统一 takeReward() + closeCurrentScreen()。
            // 以前这里**无条件**只调 acquireCard，于是【发现】选出来的牌没人接手 —— 试玩 agent 实测
            // 「take_card 返回 applied、ui 回到 combat，但手牌没变」（issue #19）。
            boolean chooseOne = Boolean.TRUE.equals(spirelink.util.FieldKit.fieldOrNull(screen, "chooseOne"));
            boolean codex = Boolean.TRUE.equals(spirelink.util.FieldKit.fieldOrNull(screen, "codex"));
            boolean discovery = Boolean.TRUE.equals(spirelink.util.FieldKit.fieldOrNull(screen, "discovery"));
            if (chooseOne) {
                card.onChoseThisOption();
                spirelink.util.Diag.info("take_card: chooseOne → onChoseThisOption(" + card.cardID + ")");
            } else if (codex) {
                spirelink.util.FieldKit.setFieldQuiet(screen, "codexCard", card);
                spirelink.util.Diag.info("take_card: codex → codexCard=" + card.cardID);
            } else if (discovery) {
                spirelink.util.FieldKit.setFieldQuiet(screen, "discoveryCard", card);
                spirelink.util.Diag.info("take_card: discovery → discoveryCard=" + card.cardID);
            } else {
                java.lang.reflect.Method acquire = CardRewardScreen.class
                        .getDeclaredMethod("acquireCard", AbstractCard.class);
                acquire.setAccessible(true);
                acquire.invoke(screen, card);
                spirelink.util.Diag.info("take_card: acquireCard(" + card.cardID + ") 已调用");
            }
            // The screen closes itself in the vanilla flow; if it did not, close it the way the
            // game does after a pick.
            java.lang.reflect.Method take = CardRewardScreen.class.getDeclaredMethod("takeReward");
            take.setAccessible(true);
            take.invoke(screen);
            spirelink.util.Diag.info("take_card: takeReward() 已调用");
            // Some reward variants (choose-one / discovery / card reward on top of the reward list)
            // keep the screen open; settle the reward item and close it the way the game does.
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
                if (screen.rItem != null) {
                    screen.rItem.isDone = true;
                }
                AbstractDungeon.closeCurrentScreen();
                spirelink.util.Diag.info("take_card: 屏幕未关闭，已手动 closeCurrentScreen()");
            }
            return ActionResult.ok();
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            spirelink.util.Diag.warn("take_card 失败: " + cause);
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "取牌失败: " + cause);
        }
    }

    /**
     * {@code skip_card}: the <b>native</b> skip path — exactly what {@code SkipCardButton.update()}
     * runs when a human presses 跳过 (C7-01).
     *
     * <p>Checked against the shipped jar with {@code javap -c}:</p>
     * <ul>
     *   <li>{@code CardRewardScreen.skippedCards()} is <b>only</b> {@code recordMetrics("SKIP")} —
     *       it closes nothing, which is why the old implementation had to detect "屏幕没关" and close
     *       the screen by hand;</li>
     *   <li>{@code SkipCardButton.update()} on click: {@code hb.clicked = false} then
     *       {@code AbstractDungeon.closeCurrentScreen()}. <b>That</b> is the native skip, and it runs
     *       unconditionally instead of as a rescue branch;</li>
     *   <li>the reward item is settled afterwards with the screen's own {@code takeReward()} — the
     *       same call {@link #takeCard} makes after a pick. Vanilla only calls it from the pick paths,
     *       so a vanilla skip leaves the "Card Reward" row in the combat reward list and the agent
     *       could re-open the same three cards; {@code skip_card} means "I do not want this reward",
     *       so the row is consumed. Never {@code rItem.isDone = true} here: a done, still-listed card
     *       reward is re-opened by {@code CombatRewardScreen.rewardViewUpdate} through
     *       {@code claimReward()}.</li>
     * </ul>
     *
     * <p>And the button only exists on flavours vanilla lets you skip ({@code draftOpen} /
     * {@code chooseOneOpen} / {@code discoveryOpen} hide it), so the advertisement
     * ({@link ActionPlanner#cardReward}), this guard and the button all ask
     * {@link CardRewardPolicy#skipAvailable}: advertised ⟺ executable.</p>
     */
    private static ActionResult skipCard() {
        try {
            CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
            if (screen == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌界面");
            }
            if (!CardRewardPolicy.skipAvailable(screen.rewardGroup != null,
                    ActionPlanner.skipButtonVisible())) {
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                        CardRewardPolicy.notSkippableMessage());
            }
            screen.skippedCards();
            AbstractDungeon.closeCurrentScreen();
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
                spirelink.util.Diag.warn("skip_card: closeCurrentScreen() 之后屏幕还开着（screen="
                        + AbstractDungeon.screen + "）");
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "跳过没生效（选牌界面还开着），改用 take_card");
            }
            settleRewardItemAfterSkip(screen);
            spirelink.util.Diag.info("skip_card: skippedCards() + closeCurrentScreen()"
                    + "（原生跳过路径，与跳过按钮同源），奖励项已结算");
            return ActionResult.verified();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "跳过失败: " + t);
        }
    }

    /**
     * Drops the skipped card reward from the combat reward list (C7-01).
     *
     * <p>Uses the screen's own private {@code takeReward()} — the very call {@code take_card} makes
     * after a pick: it removes {@code rItem} from {@code combatRewardScreen.rewards}, repositions the
     * remaining rows and flips {@code hasTakenAll} / shows the proceed button when the list empties.
     * Done deliberately <i>after</i> the native close, so the log no longer reads like a rescue
     * ("屏幕没关 → 手动关屏 → 二次 takeReward"). Nothing to settle for the flavours that open without
     * a reward item (codex / discovery report {@code rItem == null}).</p>
     */
    private static void settleRewardItemAfterSkip(CardRewardScreen screen) {
        if (screen == null || screen.rItem == null) {
            return;
        }
        try {
            java.lang.reflect.Method take = CardRewardScreen.class.getDeclaredMethod("takeReward");
            take.setAccessible(true);
            take.invoke(screen);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("skip_card: 结算奖励项失败（跳过本身已生效）: " + t);
        }
    }

    /** The relics the Boss-relic screen really offers, read the same way {@link #claimRelic} reads them. */
    private static java.util.List<?> bossRelicList(Object screen) {
        try {
            Object relics = screen == null ? null
                    : basemod.ReflectionHacks.getPrivate(screen, screen.getClass(), "relics");
            return relics instanceof java.util.List ? (java.util.List<?>) relics : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "合法范围 0..2（共 3 个）" for {@code claim_relic} (C8-01) — same wording as take_reward/take_card. */
    private static String bossRelicRange(Object screen) {
        java.util.List<?> list = bossRelicList(screen);
        return IndexListText.range(list == null ? 0 : list.size(), "个",
                "这一屏没有可选遗物（合法范围为空）");
    }

    /**
     * "可选遗物: 0=小盒子(Tiny Chest), 1=…" — so an out-of-range {@code claim_relic} can be retried
     * with an index that exists (C8-01, the shape take_reward/take_card already answer with).
     */
    private static String bossRelicSummary(Object screen) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        java.util.List<?> list = bossRelicList(screen);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                Object relic = list.get(i);
                if (relic instanceof AbstractRelic) {
                    entries.add(IndexListText.entry(i, ((AbstractRelic) relic).name,
                            ((AbstractRelic) relic).relicId));
                }
            }
        }
        return "可选遗物: " + IndexListText.list(entries, "（这一屏没有可选遗物）");
    }

    /**
     * {@code claim_relic} (C8-01).
     *
     * <p>The index is strict ({@link IntArgument}: {@code 1.5} used to be truncated to 1, #111) and
     * every rejection names the legal range plus the relics this screen really offers — the shape
     * {@code take_reward} (#101) and {@code take_card} (C7-04) already answer with. The old message
     * was a bare {@code "遗物序号越界: 9"}: no range, no candidates, so the caller could only guess
     * again.</p>
     */
    private static ActionResult claimRelic(JsonObject payload) {
        Object screen = AbstractDungeon.bossRelicScreen;
        Integer requested;
        try {
            requested = IntArgument.optionalNonNegative(payload, "index");
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage() + "；" + bossRelicRange(screen)
                    + "；" + bossRelicSummary(screen));
        }
        int index = requested == null ? -1 : requested.intValue();
        try {
            Object relics = screen == null ? null
                    : basemod.ReflectionHacks.getPrivate(screen, screen.getClass(), "relics");
            int count = relics instanceof java.util.List ? ((java.util.List<?>) relics).size() : -1;
            spirelink.util.Diag.info("claim_relic: screen=" + AbstractDungeon.screen
                    + " relics=" + count + " index=" + index);
            if (screen == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有遗物选择界面");
            }
            if (!(relics instanceof java.util.List)) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "读不到遗物列表");
            }
            java.util.List<?> list = (java.util.List<?>) relics;
            if (index < 0) {
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        "缺少 index（payload 要写 {\"index\": <0 起的遗物序号>}）；"
                                + bossRelicRange(screen) + "；" + bossRelicSummary(screen));
            }
            if (index >= list.size()) {
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        "payload.index=" + index + " 越界：" + bossRelicRange(screen)
                                + "；" + bossRelicSummary(screen));
            }
            if (BossChestGate.relicClaimed()) {
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                        "Boss 遗物已经处理过了（" + BossChestGate.describe() + "）");
            }
            // Vanilla's relicObtainLogic casts the current room to TreasureRoomBoss; outside it the
            // screen would throw on the next update and take the render loop with it.
            if (!(AbstractDungeon.getCurrRoom()
                    instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss)) {
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "当前房间不是 Boss 宝箱房，点遗物会让游戏崩："
                                + AbstractDungeon.getCurrRoom().getClass().getSimpleName());
            }
            AbstractRelic relic = (AbstractRelic) list.get(index);
            if (relicOpensGridOnEquip(relic.relicId)) {
                // #52: this relic's effect *is* a screen opened from onEquip(). The native click
                // below cannot deliver it in time (see {@link #takeGridRelic}), so use the
                // deterministic order instead of racing the relic's landing animation.
                return takeGridRelic(relic, screen);
            }
            // CommunicationMod's technique: force the hitbox *inside* AbstractRelic.update(), right
            // after its own hb.update() recomputed hover, so the game's click handler runs normally.
            // Writing hb.hovered from here does not survive that recomputation.
            spirelink.patches.BossRelicClickPatch.request(relic);
            com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft = true;
            JsonObject verify = new JsonObject();
            verify.addProperty("index", index);
            verify.addProperty("relic", relic.relicId);
            DelayedActions.schedule(600L, ActionTypes.VERIFY_CLAIM_RELIC, verify);
            spirelink.util.Diag.info("claim_relic: 请求点击 relic #" + index + " → " + relic.relicId
                    + "（BossRelicClickPatch），screen=" + AbstractDungeon.screen);
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("claim_relic", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "选遗物失败: " + t);
        }
    }

    // ---------------------------------------------------------------- #52 relic pickup order

    /** One ordered step of {@link #planRelicPickup(String, boolean)}. */
    public enum RelicPickupStep {
        /** Close the screen that is up (BOSS_REWARD) before touching the relic. */
        CLOSE_SCREEN,
        /** Take the relic - {@code onEquip()} runs inside this call. */
        OBTAIN,
        /** Whatever screen is up after the pickup belongs to the relic (GRID): never close it. */
        KEEP_GRID
    }

    /**
     * #52: the order in which {@code claim_relic} must close, then take, a relic whose effect is a
     * screen opened from {@code onEquip()} (Empty Cage -> 移除 2 张, Astrolabe -> 转化 3 张).
     *
     * <p>Pure on purpose: the ordering *is* the bug, so it is unit-tested without a running game.
     * Two rules come straight from the shipped jar:
     * <ul>
     *   <li>a screen that is already up has to be closed <em>first</em>.
     *       {@code AbstractDungeon.closeCurrentScreen()} restores {@code previousScreen} when one is
     *       set, and {@code EmptyCage.onEquip()} writes the BOSS_REWARD screen into
     *       {@code previousScreen} whenever {@code AbstractDungeon.isScreenUp} is true. Opening the
     *       grid first and closing the relic screen afterwards therefore reopens the relic screen
     *       instead of returning to the room.</li>
     *   <li>after a relic that opens a grid, {@link RelicPickupStep#KEEP_GRID} says nothing may
     *       close a screen any more: the grid <em>is</em> the effect, closing it loses the pick.</li>
     * </ul>
     *
     * @param screenName     {@code AbstractDungeon.screen.name()}; {@code null}/"NONE" means no screen
     * @param relicOpensGrid true when this relic's {@code onEquip()} opens a grid screen
     */
    public static java.util.List<RelicPickupStep> planRelicPickup(String screenName,
                                                                 boolean relicOpensGrid) {
        boolean screenUp = screenName != null && !"NONE".equalsIgnoreCase(screenName);
        java.util.List<RelicPickupStep> steps = new java.util.ArrayList<RelicPickupStep>(3);
        if (screenUp) {
            steps.add(RelicPickupStep.CLOSE_SCREEN);
        }
        steps.add(RelicPickupStep.OBTAIN);
        if (relicOpensGrid) {
            steps.add(RelicPickupStep.KEEP_GRID);
        }
        return steps;
    }

    /**
     * True for the vanilla relics whose {@code onEquip()} opens {@code GridCardSelectScreen}.
     *
     * <p>Checked against the shipped jar with {@code javap -c}: Empty Cage, Astrolabe, Pandora's Box,
     * Calling Bell, DollysMirror and the three Bottled relics call {@code open(...)} /
     * {@code openConfirmationGrid(...)} from {@code onEquip()}; no other relic does.
     */
    public static boolean relicOpensGridOnEquip(String relicId) {
        if (relicId == null) {
            return false;
        }
        return java.util.Arrays.asList(
                "Empty Cage", "Astrolabe", "Pandora's Box", "Calling Bell", "DollysMirror",
                "Bottled Flame", "Bottled Lightning", "Bottled Tornado").contains(relicId);
    }

    private static String screenName(AbstractDungeon.CurrentScreen screen) {
        return screen == null ? "NONE" : screen.name();
    }

    /**
     * #52: take a boss relic whose effect is a multi-select grid opened from {@code onEquip()}.
     *
     * <p>Why the native click path cannot be used here: {@code AbstractRelic.update()} ->
     * {@code bossObtainLogic()} only calls {@code obtain()} (relic into the bar, no {@code onEquip}),
     * and {@code onEquip()} is not called until the icon finishes sliding into the relic bar ~1 s
     * later, from inside the next {@code AbstractRelic.update()} (bytecode offset 436). Meanwhile
     * {@code BossRelicSelectScreen.update()} has already closed the BOSS_REWARD screen in the same
     * frame as the click (offset 247), so for ~1 s the mod reports {@code ok} and {@code ui=treasure}
     * with {@code legal_actions=[proceed]}. An agent that follows that state leaves the act before the
     * grid exists and the effect vanishes without a trace - exactly #52.
     *
     * <p>{@code instantObtain()} (the no-argument one) calls {@code onEquip()} synchronously
     * (offset 135), so the grid is up inside this very action, before the caller can read a state.
     * The order still matters: the BOSS_REWARD screen has to go first, because
     * {@code EmptyCage.onEquip()} would otherwise record it as {@code previousScreen} and the grid's
     * confirm-close would reopen the relic screen.
     */
    private static ActionResult takeGridRelic(AbstractRelic relic, Object screen) {
        try {
            for (RelicPickupStep step : planRelicPickup(screenName(AbstractDungeon.screen), true)) {
                if (step == RelicPickupStep.CLOSE_SCREEN) {
                    spirelink.util.Diag.info("claim_relic: 先关 " + AbstractDungeon.screen
                            + " 再 instantObtain " + relic.relicId + "（#52 屏幕时序）");
                    AbstractDungeon.closeCurrentScreen();
                } else if (step == RelicPickupStep.OBTAIN) {
                    // 无参版会同步回调 onEquip()：网格当场打开，不用等遗物滑进遗物栏。
                    relic.instantObtain();
                }
                // KEEP_GRID: 之后什么都不做 —— 这块屏幕就是这张遗物的效果，关掉等于白拿。
            }
            spirelink.util.FieldKit.setFieldQuiet(AbstractDungeon.getCurrRoom(),
                    "choseRelic", Boolean.TRUE);
            // vanilla relicObtainLogic 的收尾：停住房间的奖励弹出计时，遗物屏不会再弹回来。
            spirelink.util.FieldKit.setFieldQuiet(AbstractDungeon.getCurrRoom(),
                    "rewardPopOutTimer", Float.valueOf(99999.0f));
            if (screen != null) {
                spirelink.util.FieldKit.setFieldQuiet(screen, "isDone", Boolean.FALSE);
            }
            AbstractDungeon.CurrentScreen now = AbstractDungeon.screen;
            spirelink.util.Diag.info("claim_relic: " + relic.relicId + " 已收入，ui="
                    + screenName(now) + (now == AbstractDungeon.CurrentScreen.GRID
                            ? "（onEquip 的选牌网格已打开）" : "（该遗物没有打开网格）"));
            // Issue #93: instantObtain() runs relicTip() too, so the RELIC_TIP can be up right now.
            // Clicking its got-it button never touches the grid this relic may have opened.
            dismissFollowUpTip("take_grid_relic");
            spirelink.ui.AgentHud.push("取了 Boss 遗物 " + relic.relicId);
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("claim_relic_grid", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "取遗物失败: " + t);
        }
    }

    /**
     * Follow-up to {@link #claimRelic}: if the native click did not land within 600 ms, take the relic
     * directly (same effect as a pick) so the run can never stall on the screen.
     */
    private static ActionResult verifyClaimRelic(JsonObject payload) {
        try {
            String relicId = str(payload, "relic", "");
            int index = num(payload, "index", -1);
            boolean owned = AbstractDungeon.player != null && !relicId.isEmpty()
                    && AbstractDungeon.player.hasRelic(relicId);
            boolean leftScreen = AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD;
            if (owned) {
                // Issue #93: the first relic pickup makes vanilla pop its RELIC_TIP FtueTip
                // (AbstractRelic.obtain() -> relicTip()), which lands on top of the reward screen.
                // Take it down inside the claim so the agent is never left staring at tutorial_tip.
                dismissFollowUpTip("verify_claim_relic(owned)");
                spirelink.util.Diag.info("claim_relic: 原生点击生效 owned=true leftScreen=" + leftScreen);
                return ActionResult.ok();
            }
            // Issue #93: "leftScreen" used to count as success — it does not. AbstractDungeon.screen
            // leaves BOSS_REWARD whenever *anything* else takes the screen (the RELIC_TIP FtueTip is
            // exactly that), so a claim whose click never landed looked done and the relic was gone
            // for good (QA: 2 claims, 1 relic lost). obtain() adds to player.relics synchronously, so
            // hasRelic() is the only honest proof.
            spirelink.util.Diag.warn("claim_relic: 原生点击没生效 owned=false leftScreen=" + leftScreen
                    + "，改走直接收取");
            // The tip can already be up at this point; clear it before touching the relic so the
            // obtain path is not fighting a FTUE screen.
            dismissFollowUpTip("verify_claim_relic(not owned)");
            Object screen = AbstractDungeon.bossRelicScreen;
            Object relics = screen == null ? null
                    : basemod.ReflectionHacks.getPrivate(screen, screen.getClass(), "relics");
            if (!(relics instanceof java.util.List) || index < 0
                    || index >= ((java.util.List<?>) relics).size()) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "遗物列表已消失，重新 get_state");
            }
            spirelink.patches.BossRelicClickPatch.cancel();
            AbstractRelic relic = (AbstractRelic) ((java.util.List<?>) relics).get(index);
            boolean opensGrid = relicOpensGridOnEquip(relic.relicId);
            spirelink.util.Diag.warn("claim_relic: 原生点击没生效，直接收入 " + relic.relicId
                    + (opensGrid ? "（onEquip 会开网格）" : ""));
            // #52: same order as {@link #takeGridRelic} - close the relic screen first, then take
            // the relic so onEquip() can open its grid into a clean screen, and never close after.
            for (RelicPickupStep step : planRelicPickup(screenName(AbstractDungeon.screen), opensGrid)) {
                if (step == RelicPickupStep.CLOSE_SCREEN) {
                    AbstractDungeon.closeCurrentScreen();
                } else if (step == RelicPickupStep.OBTAIN) {
                    relic.isObtained = true;
                    relic.instantObtain();
                    spirelink.util.FieldKit.setFieldQuiet(AbstractDungeon.getCurrRoom(),
                            "choseRelic", Boolean.TRUE);
                    spirelink.util.FieldKit.setFieldQuiet(AbstractDungeon.getCurrRoom(),
                            "rewardPopOutTimer", Float.valueOf(99999.0f));
                    if (screen != null) {
                        spirelink.util.FieldKit.setFieldQuiet(screen, "isDone", Boolean.FALSE);
                    }
                }
                // KEEP_GRID: 遗物自己的网格就是效果本身，收尾时不能关。
            }
            if (!opensGrid && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD) {
                AbstractDungeon.closeCurrentScreen();
            }
            // The fallback obtain() above raises the same RELIC_TIP; take it down as well (#93).
            dismissFollowUpTip("verify_claim_relic(taken)");
            spirelink.ui.AgentHud.push("取了 Boss 遗物 " + relic.relicId);
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("verify_claim_relic", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "收遗物失败: " + t);
        }
    }

    /** {@code skip_relic}: the boss relic screen's own cancel ("skip") button. */
    private static ActionResult skipRelic() {
        Object screen = AbstractDungeon.bossRelicScreen;
        if (screen == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有 Boss 遗物界面");
        }
        try {
            spirelink.patches.BossRelicClickPatch.cancel();
            java.lang.reflect.Method noPick = screen.getClass().getMethod("noPick");
            noPick.invoke(screen);
            boolean left = AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD;
            if (!left) {
                AbstractDungeon.closeCurrentScreen();
                left = AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD;
            }
            BossChestGate.markSkipped();
            spirelink.util.Diag.info("skip_relic: noPick() 调用后 left=" + left
                    + " screen=" + AbstractDungeon.screen + "（" + BossChestGate.describe() + "）");
            if (!left) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "跳过遗物没生效，稍后重试");
            }
            return ActionResult.verified();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("skip_relic", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "跳过遗物失败: " + t);
        }
    }

    private static String campfireActionOf(String simpleName) {
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

    /** Campfire options expose a public {@code useOption()}, so this is fully deterministic. */
    private static ActionResult campfire(String wanted) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        try {
            // Declared on RestRoom, not AbstractRoom (reading through the superclass used to kill
            // the render loop with NoSuchFieldException).
            if (!(room instanceof com.megacrit.cardcrawl.rooms.RestRoom)) {
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这里不是篝火");
            }
            Object ui = ((com.megacrit.cardcrawl.rooms.RestRoom) room).campfireUI;
            if (ui == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "篝火界面还没准备好");
            }
            // CampfireUI tracks "something was chosen" in this flag; while it is false the buttons
            // stay clickable, which is why calling useOption() alone let the agent choose twice.
            Object somethingSelected = spirelink.util.FieldKit.fieldOrNull(ui, "somethingSelected");
            if (Boolean.TRUE.equals(somethingSelected)) {
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "篝火选项已经选过了");
            }
            Object buttons = spirelink.util.FieldKit.fieldOrNull(ui, "buttons");
            if (!(buttons instanceof java.util.List)) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "读不到篝火选项");
            }
            for (Object option : (java.util.List<?>) buttons) {
                if (option == null) {
                    continue;
                }
                if (wanted.equals(campfireActionOf(option.getClass().getSimpleName()))) {
                    // A second use of a spent option is what froze the render loop; refuse it here
                    // even if the planner somehow still offers it.
                    if (!ActionPlanner.campfireUsable(option)) {
                        return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "篝火选项已经用过了");
                    }
                    if ("smith".equals(wanted)) {
                        // SmithOption.useOption() only queues a CampfireSmithEffect, which opens the
                        // upgrade grid about a second later - and the effect ALSO upgrades whatever
                        // ends up in selectedCards. Open the grid now and mark the effect as opened so
                        // it does not re-open it, then verify the screen really changed.
                        com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect effect =
                                new com.megacrit.cardcrawl.vfx.campfire.CampfireSmithEffect();
                        spirelink.util.FieldKit.setFieldOrNull(effect, "openedScreen", Boolean.TRUE);
                        spirelink.util.InputReset.clearStaleClicks();
                        AbstractDungeon.effectList.add(effect);
                        AbstractDungeon.gridSelectScreen.open(
                                AbstractDungeon.player.masterDeck.getUpgradableCards(), 1,
                                com.megacrit.cardcrawl.ui.campfire.SmithOption.TEXT[0],
                                true, false, true, false);
                        spirelink.util.FieldKit.setFieldOrNull(ui, "somethingSelected", Boolean.TRUE);
                        spirelink.util.FieldKit.setFieldOrNull(ui, "touchOption", null);
                        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
                            return ActionResult.reject(ErrorCodes.NOT_READY,
                                    "升级界面没打开（screen=" + AbstractDungeon.screen + "）");
                        }
                        spirelink.util.Diag.info("篝火: smith 直接打开升级网格（可升级牌 "
                                + AbstractDungeon.player.masterDeck.getUpgradableCards().size() + " 张）");
                        return ActionResult.ok();
                    }
                    option.getClass().getMethod("useOption").invoke(option);
                    // Mirror what CampfireUI.update() does after a real click, so the UI locks itself.
                    spirelink.util.FieldKit.setFieldOrNull(ui, "somethingSelected", Boolean.TRUE);
                    spirelink.util.FieldKit.setFieldOrNull(ui, "touchOption", null);
                    spirelink.util.Diag.info("篝火: 已执行 " + wanted + "（somethingSelected=true）");
                    return ActionResult.ok();
                }
            }
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "篝火没有 " + wanted + " 选项");
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "篝火选项失败: " + t);
        }
    }

    // ---------------------------------------------------------------- shop (C8-01)

    /** "合法范围 0..2（共 3 张）" for the shelf {@code kind} names — the wording take_reward/take_card use. */
    private static String shopRangeText(String kind, ShopScreen screen) {
        if ("card".equals(kind)) {
            return IndexListText.range(ActionPlanner.shopCards(screen).size(), "张",
                    "这一架没有可买的牌（合法范围为空）");
        }
        if ("relic".equals(kind)) {
            return IndexListText.range(ShopAccess.relics(screen).size(), "个",
                    "这一架没有可买的遗物（合法范围为空）");
        }
        if ("potion".equals(kind)) {
            return IndexListText.range(ShopAccess.potions(screen).size(), "瓶",
                    "这一架没有可买的药水（合法范围为空）");
        }
        return "kind 只有 card / relic / potion（见 legal_actions 的 buy payload）";
    }

    /** "可买: 0=打击(Strike)（50 金）, 1=…" — the shelf {@code kind} names (C8-01). */
    private static String shopShelfSummary(String kind, ShopScreen screen) {
        if ("card".equals(kind)) {
            return shopCardsSummary(ActionPlanner.shopCards(screen));
        }
        if ("relic".equals(kind)) {
            return shopRelicsSummary(ShopAccess.relics(screen));
        }
        if ("potion".equals(kind)) {
            return shopPotionsSummary(ShopAccess.potions(screen));
        }
        return "可选商品见 legal_actions 的 buy payload";
    }

    private static String shopCardsSummary(java.util.List<AbstractCard> cards) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        for (int i = 0; i < cards.size(); i++) {
            AbstractCard card = cards.get(i);
            if (card == null) {
                continue;
            }
            entries.add(IndexListText.entry(i, card.name, card.cardID) + priceText(card.price));
        }
        return "可买: " + IndexListText.list(entries, "（这一架没有可买的牌）");
    }

    private static String shopRelicsSummary(java.util.List<?> relics) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        for (int i = 0; i < relics.size(); i++) {
            Object raw = relics.get(i);
            if (!(raw instanceof com.megacrit.cardcrawl.shop.StoreRelic)) {
                continue;
            }
            com.megacrit.cardcrawl.shop.StoreRelic store =
                    (com.megacrit.cardcrawl.shop.StoreRelic) raw;
            if (store.relic == null) {
                continue;
            }
            entries.add(IndexListText.entry(i, store.relic.name, store.relic.relicId)
                    + (store.isPurchased ? "（已售出）" : "（" + store.price + " 金）"));
        }
        return "可买: " + IndexListText.list(entries, "（这一架没有可买的遗物）");
    }

    private static String shopPotionsSummary(java.util.List<?> potions) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        for (int i = 0; i < potions.size(); i++) {
            Object raw = potions.get(i);
            if (!(raw instanceof com.megacrit.cardcrawl.shop.StorePotion)) {
                continue;
            }
            com.megacrit.cardcrawl.shop.StorePotion store =
                    (com.megacrit.cardcrawl.shop.StorePotion) raw;
            if (store.potion == null) {
                continue;
            }
            entries.add(IndexListText.entry(i, store.potion.name, store.potion.ID)
                    + (store.isPurchased ? "（已售出）" : "（" + store.price + " 金）"));
        }
        return "可买: " + IndexListText.list(entries, "（这一架没有可买的药水）");
    }

    /** "（50 金）" or "（已售出）" — one shelf entry's state, never a price the shelf no longer has. */
    private static String priceText(int price) {
        return price <= 0 ? "（已售出）" : "（" + price + " 金）";
    }

    /**
     * {@code buy} (C8-01).
     *
     * <p>{@code kind} / {@code index} are payload, so they are answered before anything is bought,
     * and every rejection carries the legal range plus the shelf that can really be named — the
     * shape {@code take_reward} (#101) and {@code take_card} (C7-04) already answer with. The old
     * messages were bare ({@code "卡牌序号越界: 9"} / {@code "遗物序号越界: 9"}), so a caller could
     * only guess again. The index is strict ({@link IntArgument}: {@code 1.5} used to be truncated
     * to 1, #111).</p>
     */
    private static ActionResult buy(JsonObject payload) {
        ShopScreen screen = AbstractDungeon.shopScreen;
        if (screen == null || !screen.isActive) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "商店没开着（先 open_shop）");
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "还没有玩家");
        }
        // C8-01: `kind` was read with getAsString(), which throws on a non-primitive and then came
        // back as NOT_READY "购买失败: ..." — a payload error reported as a state error (#110 shape).
        String kind = str(payload, "kind", "");
        int index;
        try {
            index = IntArgument.requiredNonNegative(payload, "index",
                    "缺少 index（payload 要写 {\"kind\": \"card\"|\"relic\"|\"potion\","
                            + " \"index\": <0 起的序号>}，序号见 legal_actions 的 buy payload）");
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage() + "；"
                    + shopRangeText(kind, screen) + "；" + shopShelfSummary(kind, screen));
        }
        try {
            if ("card".equals(kind)) {
                java.util.List<AbstractCard> cards = ActionPlanner.shopCards(screen);
                if (index >= cards.size()) {
                    // C8-01: the legal range plus the shelf, exactly like take_reward/take_card.
                    return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                            "payload.index=" + index + " 越界：" + shopRangeText(kind, screen)
                                    + "；" + shopCardsSummary(cards));
                }
                AbstractCard card = cards.get(index);
                if (card == null || card.price <= 0) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "这张牌已经卖掉了");
                }
                if (player.gold < card.price) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "钱不够：" + card.name + " 要 " + card.price + " 金，你有 "
                                    + player.gold);
                }
                player.gold -= card.price;
                // Keep the card in its slot and only mark it sold: removing it renumbered every later
                // index, so the agent's next "buy index 4" hit the wrong item.
                card.price = -1;
                player.masterDeck.addToTop(card.makeCopy());
                spirelink.util.Diag.info("buy: 买卡 " + card.cardID + " price=" + card.price);
                return ActionResult.ok();
            }
            if ("relic".equals(kind)) {
                java.util.List<?> list = spirelink.actions.ShopAccess.relics(screen);
                if (index >= list.size()) {
                    // C8-01: range + candidates (the old message was a bare 「遗物序号越界」).
                    return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                            "payload.index=" + index + " 越界：" + shopRangeText(kind, screen)
                                    + "；" + shopRelicsSummary(list));
                }
                com.megacrit.cardcrawl.shop.StoreRelic store =
                        (com.megacrit.cardcrawl.shop.StoreRelic) list.get(index);
                if (store == null || store.isPurchased || store.relic == null) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "这个遗物已经卖掉了");
                }
                if (player.gold < store.price) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "钱不够：" + store.relic.name + " 要 " + store.price + " 金，你有 "
                                    + player.gold);
                }
                player.gold -= store.price;
                store.isPurchased = true;
                store.relic.instantObtain();
                spirelink.util.Diag.info("buy: 买遗物 " + store.relic.relicId + " price="
                        + store.price);
                return ActionResult.ok();
            }
            if ("potion".equals(kind)) {
                java.util.List<?> list = spirelink.actions.ShopAccess.potions(screen);
                if (index >= list.size()) {
                    // C8-01: range + candidates (the old message was a bare 「药水序号越界」).
                    return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                            "payload.index=" + index + " 越界：" + shopRangeText(kind, screen)
                                    + "；" + shopPotionsSummary(list));
                }
                com.megacrit.cardcrawl.shop.StorePotion store =
                        (com.megacrit.cardcrawl.shop.StorePotion) list.get(index);
                if (store == null || store.isPurchased || store.potion == null) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "这瓶药水已经卖掉了");
                }
                if (player.gold < store.price) {
                    return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                            "钱不够：" + store.potion.name + " 要 " + store.price + " 金，你有 "
                                    + player.gold);
                }
                if (!player.obtainPotion(store.potion)) {
                    // Dedicated code so the harness can tell "belt full" from "not allowed".
                    // Deliberately NOT param level: the planner approximates this with potionSlotsFull()
                    // while the executor asks the game (obtainPotion) - if those two drift, issue #103
                    // is back and the mismatch WARN should say so.
                    return ActionResult.reject(ErrorCodes.POTION_SLOT_FULL,
                            "药水槽满了，先喝掉/丢掉一瓶");
                }
                player.gold -= store.price;
                store.isPurchased = true;
                spirelink.util.Diag.info("buy: 买药水 " + store.potion.ID + " price="
                        + store.price);
                return ActionResult.ok();
            }
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "未知商品类型: " + kind + "（kind 只有 card / relic / potion）");
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buy", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "购买失败: " + t);
        }
    }

    /** Opens the merchant screen (vanilla only does this on a real click on the merchant). */
    private static ActionResult openShop() {
        ShopScreen screen = AbstractDungeon.shopScreen;
        if (!(AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.ShopRoom)) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这里不是商店");
        }
        if (screen == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "商店还没生成");
        }
        if (screen.isActive && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "商店已经打开了");
        }
        if (AbstractDungeon.isFadingOut || AbstractDungeon.isFadingIn) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "正在过场，稍后再开商店");
        }
        // A stale CancelButton click closes the screen on its very next update (show() clears
        // hb.hovered but not hb.clicked), which looked like "applied but nothing happened".
        spirelink.util.InputReset.clearStaleClicks();
        try {
            screen.open();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "打开商店失败: " + t);
        }
        spirelink.util.InputReset.clearStaleClicks();
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.SHOP) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "商店没打开（screen=" + AbstractDungeon.screen + "）");
        }
        spirelink.util.Diag.info("open_shop: 商店界面已打开（screen=SHOP，已清理残留点击）");
        return ActionResult.ok();
    }

    /** Purge service: opens the removal grid; ShopRoom.updatePurge() takes the gold on confirm. */
    private static ActionResult purgeCard() {
        ShopScreen screen = AbstractDungeon.shopScreen;
        AbstractPlayer player = AbstractDungeon.player;
        if (screen == null || player == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在不在商店里");
        }
        if (!screen.isActive) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "商店没开着（先 open_shop）");
        }
        if (!screen.purgeAvailable) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这一局的重塑服务已经用过了");
        }
        if (player.gold < ShopScreen.actualPurgeCost) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    "钱不够：净化要 " + ShopScreen.actualPurgeCost + " 金，你有 " + player.gold);
        }
        try {
            // Vanilla ShopScreen calls
            //   open(getPurgeableCards(), 1, NAMES[13], forUpgrade=false, forTransform=false,
            //        canCancel=true, forPurge=true)
            // (javap -c: iconst_0 iconst_0 iconst_1 iconst_1). The old call here passed
            // forTransform=true / canCancel=false, so the grid was classified as a confirm-only
            // transform grid: no cancel_grid was advertised and a cancel was refused — while vanilla
            // does show a CancelButton and takes the gold only on the pick.
            AbstractDungeon.gridSelectScreen.open(
                    player.masterDeck.getPurgeableCards(), 1,
                    com.megacrit.cardcrawl.shop.ShopScreen.TEXT[0], false, false, true, true);
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "没打开净化选牌界面");
            }
            spirelink.util.Diag.info("purge_card: 打开净化网格（" + ShopScreen.actualPurgeCost + " 金）");
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "打开净化界面失败: " + t);
        }
    }

    private static ActionResult leaveShop() {
        try {
            // CommunicationMod's answer: do not fake a room transition, click the proceed button
            // ("离开") that the shop already has.
            AbstractDungeon.closeCurrentScreen();
            AbstractDungeon.overlayMenu.proceedButton.show();
            com.megacrit.cardcrawl.helpers.Hitbox hb =
                    (com.megacrit.cardcrawl.helpers.Hitbox) spirelink.util.FieldKit.viaBasemod(
                            AbstractDungeon.overlayMenu.proceedButton,
                            com.megacrit.cardcrawl.ui.buttons.ProceedButton.class, "hb");
            if (hb == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "读不到离开按钮的 hitbox");
            }
            hb.clicked = true;
            spirelink.util.Diag.info("leaveShop: proceedButton.show() + hb.clicked");
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "离开商店失败: " + t);
        }
    }

    private static volatile boolean eventReleaseNext;

    /** "合法范围 0..7（共 8 张）" for the minigame board (#113). */
    private static String matchCardRange() {
        return IndexListText.range(spirelink.events.MatchGame.cards().size(), "张",
                "牌面还没发出来（合法范围为空）");
    }

    /**
     * "牌面: 0=打击(Strike), 1=防御(Defend)（已配对）" — the board a rejected
     * {@code choose_match_card} may name (#113). Matched cards and the card already flipped face up
     * are marked, because neither can be picked as the next flip.
     */
    private static String matchCardSummary() {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        java.util.List<AbstractCard> board = spirelink.events.MatchGame.cards();
        AbstractCard chosen = spirelink.events.MatchGame.chosenCard();
        for (int i = 0; i < board.size(); i++) {
            AbstractCard card = board.get(i);
            if (card == null) {
                continue;
            }
            String state = spirelink.events.MatchGame.matched(card) ? "（已配对）"
                    : card == chosen ? "（已翻开，待配对）" : "";
            entries.add(IndexListText.entry(i,
                    LabelText.oneLine(spirelink.events.MatchGame.nameFor(card)), "") + state);
        }
        return "牌面: " + IndexListText.list(entries, "（牌面还没发出来）");
    }

    /**
     * {@code choose_match_card}: flips one card of the GremlinMatchGame board (#113).
     *
     * <p>Same truncation as #111/#113 elsewhere: the index was read with {@code num()} /
     * {@code getAsInt()}, so {@code choose_match_card{"index":1.5}} flipped card 1, and a bad index
     * only answered {@code "index 越界或这张牌现在不能翻"}. The index is strict now
     * ({@link IntArgument}) and every rejection names the legal range plus the board, so a retry can
     * name a card that exists.</p>
     *
     * <p>Only {@link spirelink.patches.MatchGameClickPatch} delivers the real click: the minigame
     * recomputes hover from the real mouse every frame, so the press has to happen inside
     * {@code GremlinMatchGame.update()}.</p>
     */
    private static ActionResult chooseMatchCard(JsonObject args) {
        // #113: payload first — a fractional index used to be truncated into a real flip.
        Integer requested;
        try {
            requested = IntArgument.optionalNonNegative(args, "index");
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage() + "；" + matchCardRange()
                    + "；" + matchCardSummary());
        }
        if (!spirelink.events.MatchGame.active()) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "现在不在翻牌小游戏里");
        }
        int index = requested == null ? -1 : requested.intValue();
        if (index < 0) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "缺少 index（payload 要写 {\"index\": <0 起的牌面序号>}，序号见 legal_actions 的"
                            + " choose_match_card payload）；" + matchCardRange() + "；"
                            + matchCardSummary());
        }
        if (index >= spirelink.events.MatchGame.cards().size()) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "payload.index=" + index + " 越界：" + matchCardRange() + "；"
                            + matchCardSummary());
        }
        if (!spirelink.events.MatchGame.click(index)) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "index=" + index + " 这张牌现在不能翻；" + matchCardRange() + "；"
                            + matchCardSummary());
        }
        // 0.1.5: 这张牌马上要翻开了，从此它的名字对 agent 可见（记忆游戏的"看一眼"就是这一刻）。
        spirelink.events.MatchGame.claimReveal(spirelink.events.MatchGame.cards().get(index));
        // The minigame recomputes hover from the real mouse on every frame, so the actual click has to
        // be injected inside GremlinMatchGame.update().
        spirelink.patches.MatchGameClickPatch.request(index);
        return ActionResult.ok();
    }

    private static ActionResult eventContinue() {
        try {
            // Same predicate the planner uses, so an action it offered can never be refused here.
            if (!ActionPlanner.eventDialogAdvanceable()) {
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                boolean gone = room == null || room.event == null;
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "现在没有可推进的对话"
                        + (gone ? "（事件已结束，请 proceed 离开房间）"
                                : "（这个界面没有对话框，请 proceed）"));
            }
            boolean released = eventReleaseNext;
            if (released) {
                com.megacrit.cardcrawl.helpers.input.InputHelper.justReleasedClickLeft = true;
            } else {
                com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft = true;
            }
            eventReleaseNext = !eventReleaseNext;
            spirelink.util.Diag.info("event_continue: " + (released ? "松开" : "点击")
                    + "（推进事件文本）");
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "推进对话失败: " + t);
        }
    }

    /** "合法范围 0..2（共 3 项）" for the event options on screen (#113). */
    private static String eventOptionRange(List<LargeDialogOptionButton> options) {
        return IndexListText.range(options == null ? 0 : options.size(), "项",
                "这一屏没有事件选项（合法范围为空）");
    }

    /**
     * "可选项: 0=香蕉 回复 333 生命, 1=…" — the options a rejected {@code choose_option} may name,
     * with the locked ones marked (they exist in the range but cannot be clicked, #95 / #113).
     */
    private static String eventOptionSummary(List<LargeDialogOptionButton> options) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                LargeDialogOptionButton option = options.get(i);
                if (option == null) {
                    continue;
                }
                boolean selectable = EventOptionPolicy.selectable(option.isDisabled,
                        option.hb != null);
                entries.add(IndexListText.entry(i, LabelText.oneLine(option.msg))
                        + (selectable ? "" : "（锁定，不可点）"));
            }
        }
        return "可选项: " + IndexListText.list(entries, "（这一屏没有可点的选项）");
    }

    /**
     * {@code choose_option}: clicks the event dialog option at {@code index} (issue #95 for the lock
     * rule, #113 for the payload).
     *
     * <p>#113: the index used to be read with {@code num()} — i.e. {@code getAsInt()} — so
     * {@code choose_option{"index":1.5}} clicked option 1, and an out-of-range index answered a bare
     * {@code "选项序号越界: 9"}. Both are payload problems: {@link IntArgument} refuses the
     * non-integer, and the rejection names the legal range plus the options this screen really shows
     * (with the planner's own {@code clean(msg)} label), the shape {@code take_reward} /
     * {@code take_card} answer with.</p>
     */
    private static ActionResult chooseOption(JsonObject payload) {
        List<LargeDialogOptionButton> options = ActionPlanner.dialogOptions();
        // #113: payload first, so a malformed index never gets an event-state answer.
        Integer requested;
        try {
            requested = IntArgument.optionalNonNegative(payload, "index");
        } catch (BadRequestException e) {
            return ActionResult.rejectParam(e.code, e.getMessage() + "；" + eventOptionRange(options)
                    + "；" + eventOptionSummary(options));
        }
        if (options == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有事件选项");
        }
        int index = requested == null ? -1 : requested.intValue();
        if (index < 0) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "缺少 index（payload 要写 {\"index\": <0 起的选项序号>}，序号见 legal_actions 的"
                            + " choose_option payload）；" + eventOptionRange(options) + "；"
                            + eventOptionSummary(options));
        }
        if (index >= options.size()) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "payload.index=" + index + " 越界：" + eventOptionRange(options) + "；"
                            + eventOptionSummary(options));
        }
        LargeDialogOptionButton option = options.get(index);
        if (option == null) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION, "这个选项不可点");
        }
        // Issue #95: second line of defence. The planner skips locked options, but a hand-written
        // client can still send the index - and executing it really does change the run (ForgottenAltar
        // replaced Burning Blood with Bloody Idol).
        if (!EventOptionPolicy.selectable(option.isDisabled, option.hb != null)) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    EventOptionPolicy.lockedMessage(option.msg));
        }
        String msg = option.msg == null ? "" : option.msg;
        // Simulated clicks are unreliable here: InputHelper.update() rewrites mX/mY, Hitbox.update()
        // rewrites hovered and LargeDialogOptionButton.update() clears clicked, all in the same frame.
        // Call the handler the game itself calls when the option is pressed - buttonEffect(index) -
        // and keep the click patch only as a fallback.
        // Snapshot the event UI: MaskedBandits replaces "付钱" with "继续" without moving
        // state_version, so the action has to prove its own effect or the in-flight guard sticks.
        String before = spirelink.state.EventPayload.progressToken();
        String failure = invokeButtonEffect(index);
        if (failure == null) {
            mimicCustomButtonClick();
            String after = spirelink.state.EventPayload.progressToken();
            boolean changed = !before.equals(after);
            spirelink.util.Diag.info("choose_option: buttonEffect(" + index + ") msg=" + msg
                    + " 事件界面变化=" + changed);
            return changed ? ActionResult.verified() : ActionResult.ok();
        }
        spirelink.util.Diag.warn("choose_option: buttonEffect(" + index + ") 失败（" + failure
                + "），退回模拟点击 msg=" + msg);
        spirelink.patches.DialogClickPatch.request(option);
        return ActionResult.ok();
    }

    /**
     * Events that draw their own button (GremlinWheelGame) keep a separate {@code buttonPressed} flag
     * that only the real click path sets; without it the spin never starts even after
     * {@code buttonEffect()} ran. Set it exactly like the vanilla click branch does.
     */
    private static void mimicCustomButtonClick() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            com.megacrit.cardcrawl.events.AbstractEvent event = room == null ? null : room.event;
            if (event == null) {
                return;
            }
            if (!spirelink.util.FieldKit.setFieldQuiet(event, "buttonPressed", Boolean.TRUE)) {
                return;
            }
            Object hb = spirelink.util.FieldKit.fieldOrNull(event, "buttonHb");
            if (hb instanceof Hitbox) {
                ((Hitbox) hb).hovered = false;
            }
            spirelink.util.Diag.info("choose_option: 补齐自定义按钮状态 buttonPressed=true（"
                    + event.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("mimicCustomButtonClick", t);
        }
    }

    /**
     * Calls the event's own {@code buttonEffect(int)}.
     *
     * @return null on success, otherwise why it could not be called
     */
    private static String invokeButtonEffect(int index) {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            com.megacrit.cardcrawl.events.AbstractEvent event = room == null ? null : room.event;
            if (event == null) {
                return "当前没有事件";
            }
            java.lang.reflect.Method method = null;
            for (Class<?> clazz = event.getClass(); clazz != null && method == null;
                    clazz = clazz.getSuperclass()) {
                try {
                    method = clazz.getDeclaredMethod("buttonEffect", int.class);
                } catch (NoSuchMethodException ignored) {
                    // keep walking up
                }
            }
            if (method == null) {
                return "事件没有 buttonEffect";
            }
            method.setAccessible(true);
            method.invoke(event, Integer.valueOf(index));
            return null;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            spirelink.util.CrashGuard.report("choose_option", cause);
            return String.valueOf(cause);
        }
    }

    private static ActionResult chooseGridCards(JsonObject payload) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        if (screen == null || screen.targetGroup == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌网格");
        }
        if (!cardSelectScreenUp()) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "选牌网格没开着（screen=" + AbstractDungeon.screen + "）");
        }
        String uuid = str(payload, "uuid", null);
        if (uuid == null) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST, "缺少 uuid");
        }
        AbstractCard picked = null;
        for (AbstractCard card : screen.targetGroup.group) {
            if (card != null && uuid.equals(String.valueOf(card.uuid))) {
                picked = card;
                break;
            }
        }
        if (picked == null) {
            return ActionResult.rejectParam(ErrorCodes.CARD_NOT_IN_HAND,
                    "网格里没有这张牌: " + uuid);
        }
        try {
            boolean upgradeOnly = screen.forUpgrade && !screen.forTransform;
            if (upgradeOnly) {
                // The upgrade grid keeps its choice in upgradePreviewCard, and the caller (campfire
                // smith, events) applies it once the screen is gone - so set it and close right away.
                screen.upgradePreviewCard = picked;
                if (screen.selectedCards != null && !screen.selectedCards.contains(picked)) {
                    screen.selectedCards.add(picked);
                }
                spirelink.util.Diag.info("grid: 升级选择 = " + picked.cardID + "（upgradePreviewCard）");
                return closeGrid(screen, "choose_grid_cards(upgrade)");
            }
            if (screen.selectedCards == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "网格没准备好（selectedCards=null）");
            }
            if (screen.selectedCards.contains(picked)) {
                // Idempotent: choosing the same card again must not count twice.
                return ActionResult.ok();
            }
            Object numCardsField = spirelink.util.FieldKit.fieldOrNull(screen, "numCards");
            int wanted = numCardsField instanceof Number && ((Number) numCardsField).intValue() > 0
                    ? ((Number) numCardsField).intValue() : 1;
            if (screen.selectedCards.size() >= wanted) {
                if (wanted == 1) {
                    screen.selectedCards.clear();
                } else {
                    return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                            "已经选够 " + wanted + " 张了，先 confirm_grid");
                }
            }
            // Deterministic commit: write the list the vanilla caller will read, then close the screen
            // ourselves for the one-click flavours. Virtual clicks proved unreliable (hover state is
            // recomputed every frame), and a half-applied grid is worse than a rejection.
            screen.selectedCards.add(picked);
            spirelink.util.Diag.info("grid: selectedCards += " + picked.cardID + "（"
                    + screen.selectedCards.size() + "/" + wanted + "）");
            boolean completesNow = screen.selectedCards.size() >= wanted
                    || Boolean.TRUE.equals(
                            spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming"))
                    || screen.forPurge || screen.forTransform || screen.forClarity;
            if (completesNow) {
                return closeGrid(screen, "choose_grid_cards");
            }
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("choose_grid_cards", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "选牌失败: " + t);
        }
    }

    private static ActionResult gridConfirm(boolean confirm) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        if (screen == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有选牌网格");
        }
        try {
            if (!confirm) {
                // Issue #96: a confirm-only grid has no CancelButton in vanilla, and closing it with
                // closeCurrentScreen() makes the event that opened it believe the selection finished
                // (Transmogrifier settled with an unchanged deck). Refuse it as a business error
                // instead of quietly "cancelling" something that cannot be cancelled.
                boolean canCancel = ActionPlanner.gridCanCancel(screen);
                boolean anySelectable = gridHasUnselectedCard(screen);
                boolean canConfirm = gridConfirmAccepted(screen);
                if (!GridCancelPolicy.cancelAllowed(canCancel, anySelectable, canConfirm)) {
                    return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                            GridCancelPolicy.confirmOnlyMessage());
                }
                // Cancel is an immediate escape: clear the pending pick and close.
                // (Clicking the peek button did nothing, which is how a grid ended up as a one-way trap.)
                if (screen.selectedCards != null) {
                    screen.selectedCards.clear();
                }
                screen.upgradePreviewCard = null;
                spirelink.util.FieldKit.setFieldQuiet(screen, "hoveredCard", null);
                return closeGrid(screen, "cancel_grid", true);
            }
            Object numCardsField = spirelink.util.FieldKit.fieldOrNull(screen, "numCards");
            int wanted = numCardsField instanceof Number ? ((Number) numCardsField).intValue() : 0;
            int selected = screen.selectedCards == null ? 0 : screen.selectedCards.size();
            boolean justForConfirming = Boolean.TRUE.equals(
                    spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming"));
            // C7-03: the very predicate the planner advertises with (GridConfirmPolicy, fed by
            // ActionPlanner.gridConfirmAvailable). Nothing to confirm means the action is *not on
            // offer*, which is a legality answer (ILLEGAL_ACTION) - NOT_READY would tell the caller to
            // poll and retry an action that legal_actions never contained.
            if (!gridConfirmAvailable(screen, wanted, selected, justForConfirming)) {
                return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                        GridConfirmPolicy.nothingToConfirmMessage());
            }
            return closeGrid(screen, "confirm_grid");
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "选牌网格确认失败: " + t);
        }
    }

    /**
     * Closes the grid and verifies it really closed. vanilla callers read `selectedCards` /
     * `upgradePreviewCard` after the screen is gone, so nothing is cleared here.
     */
    private static ActionResult closeGrid(GridCardSelectScreen screen, String where) {
        return closeGrid(screen, where, false);
    }

    /**
     * Closes the grid and verifies it really closed.
     *
     * <p>Every step comes from {@link GridClosePlan} so the "what does finishing this screen mean"
     * question is unit-testable without a game. {@code cancel} additionally replicates vanilla
     * {@code CancelButton}'s campfire restore (issue #90): in a rest room the cancel branch must end
     * with {@code campfireUI.reopen()}, otherwise {@code CampfireUI.hidden} /
     * {@code somethingSelected} stay true and the campfire buttons never come back.</p>
     */
    private static ActionResult closeGrid(GridCardSelectScreen screen, String where, boolean cancel) {
        try {
            java.util.List<GridClosePlan.Step> steps;
            try {
                steps = GridClosePlan.steps(currRoomClassName(), gridKindOf(screen), cancel);
            } catch (Throwable t) {
                steps = GridClosePlan.steps(null, GridClosePlan.KIND_NORMAL, cancel);
            }
            boolean closed = false;
            for (GridClosePlan.Step step : steps) {
                if (step == GridClosePlan.Step.REOPEN_CAMPFIRE) {
                    // Vanilla restores the campfire only after the screen is really gone.
                    if (closed) {
                        reopenCampfireUi(where);
                    }
                    continue;
                }
                if (closed) {
                    continue;
                }
                closed = applyGridCloseStep(screen, step);
            }
            spirelink.util.Diag.info("grid: 关闭网格（" + where + "）closed=" + closed
                    + " cancel=" + cancel + " steps=" + steps
                    + " screen=" + AbstractDungeon.screen);
            if (!closed) {
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "选牌网格关不掉（" + where + "，selected="
                                + (screen.selectedCards == null ? -1 : screen.selectedCards.size())
                                + "），稍后重试");
            }
            return ActionResult.verified();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("closeGrid", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "关闭选牌网格失败: " + t);
        }
    }

    /** True while the grid still holds a card the player has not picked yet. */
    static boolean gridHasUnselectedCard(GridCardSelectScreen screen) {
        try {
            if (screen == null || screen.targetGroup == null || screen.targetGroup.group == null) {
                return false;
            }
            for (AbstractCard card : screen.targetGroup.group) {
                if (card != null && (screen.selectedCards == null
                        || !screen.selectedCards.contains(card))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when {@code confirm_grid} would be accepted right now.
     *
     * <p>Since C7-03 this is the planner's own advertisement rule: {@link GridConfirmPolicy} fed with
     * the same numbers, so "advertised" and "accepted" are one set.</p>
     */
    static boolean gridConfirmAccepted(GridCardSelectScreen screen) {
        try {
            if (screen == null) {
                return false;
            }
            Object numCardsField = spirelink.util.FieldKit.fieldOrNull(screen, "numCards");
            int wanted = numCardsField instanceof Number ? ((Number) numCardsField).intValue() : 0;
            int selected = screen.selectedCards == null ? 0 : screen.selectedCards.size();
            boolean justForConfirming = Boolean.TRUE.equals(
                    spirelink.util.FieldKit.fieldOrNull(screen, "isJustForConfirming"));
            return gridConfirmAvailable(screen, wanted, selected, justForConfirming);
        } catch (Throwable t) {
            return false;
        }
    }

    /** One call of {@link GridConfirmPolicy} with the numbers read off the live screen (C7-03). */
    private static boolean gridConfirmAvailable(GridCardSelectScreen screen, int wanted, int selected,
            boolean justForConfirming) {
        return GridConfirmPolicy.confirmAvailable(ActionPlanner.gridConfirmAvailable(screen), wanted,
                selected, justForConfirming, screen != null && screen.upgradePreviewCard != null);
    }

    /** Runs one close step and reports whether the screen is gone afterwards. */
    private static boolean applyGridCloseStep(GridCardSelectScreen screen, GridClosePlan.Step step) {
        if (step == GridClosePlan.Step.CLOSE_SCREEN) {
            if (cardSelectScreenUp()) {
                AbstractDungeon.closeCurrentScreen();
            }
            return !cardSelectScreenUp();
        }
        if (step == GridClosePlan.Step.HIDE_GRID) {
            screen.hide();
            return !cardSelectScreenUp();
        }
        if (step == GridClosePlan.Step.RESET_SCREEN) {
            AbstractDungeon.isScreenUp = false;
            AbstractDungeon.screen = AbstractDungeon.CurrentScreen.NONE;
            return !cardSelectScreenUp();
        }
        return !cardSelectScreenUp();
    }

    /** {@code AbstractDungeon.getCurrRoom().getClass().getName()}, null when there is no room. */
    private static String currRoomClassName() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room == null ? null : room.getClass().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which vanilla grid flavour is up (upgrade / transform / purge / normal). */
    private static String gridKindOf(GridCardSelectScreen screen) {
        if (screen == null) {
            return GridClosePlan.KIND_NORMAL;
        }
        return GridClosePlan.gridKind(screen.forUpgrade, screen.forTransform, screen.forPurge);
    }

    /**
     * Vanilla {@code CancelButton}: {@code ((RestRoom) currRoom).campfireUI.reopen()} — hidden=false,
     * hideStuffTimer=0.5, somethingSelected=false (issue #90).
     */
    private static void reopenCampfireUi(String where) {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            com.megacrit.cardcrawl.rooms.CampfireUI ui = room
                    instanceof com.megacrit.cardcrawl.rooms.RestRoom
                            ? ((com.megacrit.cardcrawl.rooms.RestRoom) room).campfireUI : null;
            if (ui != null) {
                ui.reopen();
                spirelink.util.Diag.info("grid: 营火上下文取消（" + where + "）-> campfireUI.reopen()"
                        + "（hidden=false, somethingSelected=false），营火按钮回来了");
                return;
            }
            // Defensive: a rest room without a built campfireUI would otherwise stay hidden forever.
            com.megacrit.cardcrawl.rooms.CampfireUI.hidden = false;
            spirelink.util.Diag.warn("grid: 营火上下文取消（" + where
                    + "）但 campfireUI==null，只把 CampfireUI.hidden 放回 false");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("grid: 恢复营火 UI 失败（" + where + "）: " + t);
        }
    }

    /** Closes the grid screen outright; the last-resort exit for grids that offer no button. */
    private static ActionResult forceCloseGrid(GridCardSelectScreen screen) {
        spirelink.util.Diag.warn("cancel_grid: 网格没有可选/可确认/可取消，强制关闭");
        return closeGrid(screen, "force_close_grid", true);
    }

    private static ActionResult runOverProceed() {
        try {
            Object screen = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH
                    ? AbstractDungeon.deathScreen : AbstractDungeon.victoryScreen;
            if (screen == null
                    && AbstractDungeon.getCurrRoom()
                            instanceof com.megacrit.cardcrawl.rooms.VictoryRoom) {
                // The winning frame has no VictoryScreen yet; build it exactly like vanilla does so the
                // agent always has a real "back to main menu" button instead of waiting forever.
                screen = spirelink.mcp.DevRoomTool.buildVictoryScreen();
                spirelink.util.Diag.warn("runOverProceed: VictoryScreen 还不存在，已主动创建");
            }
            // Vanilla's win/death screen needs TWO clicks: the first one flips showingStats and only the
            // second leaves. A single "click" therefore looked like a no-op (queued, ui stayed victory).
            // Do what the second click does - the transition itself - and verify it synchronously.
            spirelink.util.FieldKit.setFieldQuiet(screen, "showingStats", Boolean.TRUE);
            returnToMenu();
            boolean fading = com.megacrit.cardcrawl.core.CardCrawlGame.startOver
                    || AbstractDungeon.isFadingOut || AbstractDungeon.isFadingIn;
            spirelink.util.Diag.info("runOverProceed: 已触发回主菜单（startOver="
                    + com.megacrit.cardcrawl.core.CardCrawlGame.startOver
                    + "，fading=" + fading + "，screen=" + AbstractDungeon.screen + "）");
            // startOver() only sets a flag + starts the fade; the mode flips a few frames later. Keep a
            // watchdog so a swallowed transition cannot strand the agent on the victory screen.
            DelayedActions.schedule(1500L, ActionTypes.VERIFY_RUN_OVER, new JsonObject());
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "结算界面按钮不可用: " + t);
        }
    }

    /** Post-check for a dev-created fight: intents must exist or the render loop will NPE later. */
    private static ActionResult verifyEncounter(JsonObject payload) {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            java.util.List<com.megacrit.cardcrawl.monsters.AbstractMonster> monsters =
                    room == null || room.monsters == null ? null : room.monsters.monsters;
            int missing = 0;
            if (monsters != null) {
                for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : monsters) {
                    if (monster != null
                            && spirelink.util.FieldKit.fieldOrNull(monster, "move") == null) {
                        missing++;
                    }
                }
            }
            String encounter = payload != null && payload.has("encounter")
                    && !payload.get("encounter").isJsonNull()
                            ? payload.get("encounter").getAsString() : "?";
            spirelink.util.Diag.info("verify_encounter: " + encounter + " actions="
                    + (AbstractDungeon.actionManager == null || AbstractDungeon.actionManager.actions == null
                            ? 0 : AbstractDungeon.actionManager.actions.size())
                    + " phase=" + (AbstractDungeon.actionManager == null
                            ? "-" : AbstractDungeon.actionManager.phase)
                    + " 房间="
                    + (room == null ? "-" : room.getClass().getSimpleName())
                    + " 怪物=" + (monsters == null ? 0 : monsters.size())
                    + " 缺 move=" + missing
                    + " phase=" + (room == null ? "-" : room.phase));
            com.google.gson.JsonObject readiness =
                    spirelink.mcp.DevEncounterTool.combatReadiness();
            spirelink.util.Diag.info("verify_encounter: 准备度 " + readiness);
            if (missing > 0) {
                spirelink.util.Diag.warn("verify_encounter: " + missing
                        + " 只怪物没有 move，showIntent() 会 NPE；请把这条日志发出来");
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "怪物缺少 move（" + missing + " 只），showIntent() 会崩溃");
            }
            // I4: a fight the player cannot act in is not a usable fight.
            if (!readiness.get("ready").getAsBoolean()) {
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "战斗未初始化：" + readiness);
            }
            return ActionResult.ok();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("verify_encounter", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "遭遇自检失败: " + t);
        }
    }

    /** Watchdog for {@link #runOverProceed}: re-trigger the transition if the screen is still up. */
    private static ActionResult verifyRunOver() {
        try {
            boolean stillThere = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY
                    || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH;
            if (!stillThere && !com.megacrit.cardcrawl.core.CardCrawlGame.startOver) {
                spirelink.util.Diag.info("runOverProceed: 1.5s 内已离开结算屏 ✓");
                return ActionResult.ok();
            }
            spirelink.util.Diag.warn("runOverProceed: 1.5s 后仍在结算屏（screen="
                    + AbstractDungeon.screen + "），重试 startOver()");
            returnToMenu();
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "回主菜单重试失败: " + t);
        }
    }

    /**
     * The exact transition vanilla runs on the second click of the win/death screen
     * ({@code VictoryScreen.update()} offsets 290+: clear unlocks, drop trial/daily flags, startOver).
     */
    private static void returnToMenu() {
        try {
            if (AbstractDungeon.unlocks != null) {
                AbstractDungeon.unlocks.clear();
            }
        } catch (Throwable ignored) {
            // unlock bookkeeping is cosmetic for us
        }
        try {
            com.megacrit.cardcrawl.core.Settings.isTrial = false;
            com.megacrit.cardcrawl.core.Settings.isDailyRun = false;
        } catch (Throwable ignored) {
            // same
        }
        com.megacrit.cardcrawl.core.CardCrawlGame.startOver();
    }

    /** {@code take_reward}: click the reward item; its own update claims it. */

    /** Chests this process already opened; isOpen alone is unreliable (see openChest). */
    private static final java.util.Set<Object> alreadyOpened =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Object, Boolean>());

    /** The room whose boss hand-over we started last, and when (#118). Weak: an old act is not pinned. */
    private static java.lang.ref.WeakReference<Object> handOverRoom;
    private static long handOverAtMs;

    /** How long ago we started the last boss hand-over, in ms (0 when we never started one). */
    private static long handOverAgeMs() {
        return handOverAtMs <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - handOverAtMs);
    }

    /**
     * Runs the transition {@code ProceedButton.update()} would run for a cleared boss/victory room.
     *
     * <p>The choice itself is {@link BossRewardPolicy#handOver} (pure, unit tested); this method only
     * adds the game reads and the one precondition vanilla keeps in the button: the act-1/2 boss moves
     * to the boss treasure room, where the relic pick belongs to that room's BossChest
     * ({@code BossChest.open()} calls {@code BossRelicSelectScreen.open}). The pick screen may only be
     * rendered while the current room IS a {@code TreasureRoomBoss} - opening it from
     * {@code MonsterRoomBoss} crashed the render loop with a {@code ClassCastException}.</p>
     *
     * <p>#118 (S1): the "already under way, do not restart it" test is a <b>budget</b>, not a flag.
     * The previous version asked {@code AbstractDungeon.waitingOnFadeOut}, which in this build is only
     * ever written - {@code nextRoomTransitionStart()} sets it true and <b>nothing ever clears or even
     * reads it</b> (verified by disassembling AbstractDungeon: the field is never read anywhere in the
     * jar). The first act-1/2 hand-over therefore made it permanently true, so every later
     * {@code proceed} - exactly the "leave the boss chest room" one - answered ok without running a
     * transition: state_version froze, the harness reported {@code queued} forever.</p>
     *
     * @return the vanilla method name that ran, {@link BossRewardPolicy#RELIC_PENDING},
     *         {@link BossRewardPolicy#IN_FLIGHT}, {@link BossRewardPolicy#ACT_TRANSITION_PENDING}, or
     *         {@code null} when the transition could not be started at all
     */
    private static String advanceFromBoss(
            com.megacrit.cardcrawl.ui.buttons.ProceedButton button, AbstractRoom room) {
        if (room == null) {
            return null;
        }
        try {
            Object pendingRoom = handOverRoom == null ? null : handOverRoom.get();
            int decision = BossRewardPolicy.decision(AbstractDungeon.isDungeonBeaten,
                    handOverAtMs, pendingRoom == room, System.currentTimeMillis());
            if (decision == BossRewardPolicy.ACT_TRANSITION) {
                return BossRewardPolicy.ACT_TRANSITION_PENDING;
            }
            if (decision == BossRewardPolicy.HAND_OVER_IN_FLIGHT) {
                return BossRewardPolicy.IN_FLIGHT;
            }
            if (room instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss && !bossRelicHandled()) {
                // Walking on would skip the relic for good: open the chest / pick first.
                return BossRewardPolicy.RELIC_PENDING;
            }
            boolean doubleBoss = AbstractDungeon.ascensionLevel >= 20
                    && AbstractDungeon.bossList != null && AbstractDungeon.bossList.size() > 1;
            String name = BossRewardPolicy.handOver(
                    room.getClass().getSimpleName(), AbstractDungeon.id, doubleBoss);
            if (name == null) {
                return null;
            }
            // goToNextDungeon is the only transition that takes the room (it casts to TreasureRoomBoss).
            String called = BossRewardPolicy.GO_TO_NEXT_DUNGEON.equals(name)
                    ? invokeProceed(button, name,
                            new Class<?>[] {AbstractRoom.class}, new Object[] {room})
                    : invokeProceed(button, name, new Class<?>[0], new Object[0]);
            if (called != null) {
                handOverAtMs = System.currentTimeMillis();
                handOverRoom = new java.lang.ref.WeakReference<Object>(room);
            }
            return called;
        } catch (Throwable t) {
            reportReflectionFailure(t);
            return null;
        }
    }

    /**
     * The one answer for "proceed has to move the run past this boss room" (#114 / #118).
     *
     * <p>Every way of <b>not</b> advancing is a business rejection with a reason - never a bare
     * {@code ok}. An ok without a started transition is what left the action {@code queued} until the
     * wall clock released it (#118); a rejection is answered at once and never marks in-flight.</p>
     */
    private static ActionResult handOverOrExplain(
            com.megacrit.cardcrawl.ui.buttons.ProceedButton button, AbstractRoom room) {
        String went = advanceFromBoss(button, room);
        if (BossRewardPolicy.RELIC_PENDING.equals(went)) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "Boss 遗物还没拿：先 open_chest 开 Boss 宝箱，再 claim_relic"
                            + "（现在就走会把遗物丢掉）");
        }
        if (BossRewardPolicy.ACT_TRANSITION_PENDING.equals(went)) {
            spirelink.util.Diag.info("proceed: 本幕已切换（isDungeonBeaten），等下一幕加载");
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    BossRewardPolicy.actTransitionHint(handOverAgeMs()));
        }
        if (BossRewardPolicy.IN_FLIGHT.equals(went)) {
            spirelink.util.Diag.info("proceed: 自己刚起的过场还在 fade 中（"
                    + handOverAgeMs() + "ms），不重启");
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    BossRewardPolicy.handOverInFlightHint(handOverAgeMs()));
        }
        if (went == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    BossRewardPolicy.handOverFailedHint(
                            room == null ? null : room.getClass().getSimpleName()));
        }
        spirelink.util.Diag.info("proceed: Boss 过场 " + went + "（"
                + room.getClass().getSimpleName() + "）");
        return ActionResult.ok();
    }

    /** Calls a private ProceedButton transition, logging the real cause when it blows up. */
    private static String invokeProceed(com.megacrit.cardcrawl.ui.buttons.ProceedButton button,
            String name, Class<?>[] types, Object[] args) {
        try {
            java.lang.reflect.Method method =
                    com.megacrit.cardcrawl.ui.buttons.ProceedButton.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(button, args);
            return name;
        } catch (Throwable t) {
            reportReflectionFailure(t);
            return null;
        }
    }

    private static void reportReflectionFailure(Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                && t.getCause() != null ? t.getCause() : t;
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = cause.getStackTrace();
        for (int i = 0; i < frames.length && i < 5; i++) {
            stack.append(" | ").append(frames[i]);
        }
        spirelink.util.Diag.warn("proceed: Boss 过场反射失败: " + cause + stack);
    }

    /** True when the boss relic screen was already resolved (picked or skipped). */
    /** Kept for callers that only need the gate's answer. */
    private static boolean bossRelicHandled() {
        return !BossChestGate.mustOpenChest();
    }

    /** First N stack frames of a throwable as one line, for logs that must stay single-line. */
    public static String firstFrames(Throwable t, int count) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                && t.getCause() != null ? t.getCause() : t;
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = cause.getStackTrace();
        for (int i = 0; i < frames.length && i < count; i++) {
            sb.append(i == 0 ? "" : " <- ").append(frames[i]);
        }
        return sb.toString();
    }

    /**
     * Drops everything the game queued. Used by dev tools that call game logic out of context: the
     * actions created by e.g. {@code card.use()} run on LATER frames, where the caller's stack is gone,
     * and crash inside {@code AbstractRoom.update} (RemoveAllBlockAction NPE) - outside any try/catch.
     */
    public static int clearActionQueues() {
        try {
            if (AbstractDungeon.actionManager == null) {
                return 0;
            }
            int dropped = 0;
            if (AbstractDungeon.actionManager.actions != null) {
                dropped += AbstractDungeon.actionManager.actions.size();
                AbstractDungeon.actionManager.actions.clear();
            }
            if (AbstractDungeon.actionManager.preTurnActions != null) {
                dropped += AbstractDungeon.actionManager.preTurnActions.size();
                AbstractDungeon.actionManager.preTurnActions.clear();
            }
            if (AbstractDungeon.actionManager.cardQueue != null) {
                dropped += AbstractDungeon.actionManager.cardQueue.size();
                AbstractDungeon.actionManager.cardQueue.clear();
            }
            AbstractDungeon.actionManager.currentAction = null;
            return dropped;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** First monster that is still a legal target. */
    public static AbstractMonster firstAliveMonster() {
        try {
            if (AbstractDungeon.getCurrRoom() == null
                    || AbstractDungeon.getCurrRoom().monsters == null) {
                return null;
            }
            for (AbstractMonster monster : AbstractDungeon.getCurrRoom().monsters.monsters) {
                if (monster != null && !monster.isDeadOrEscaped() && monster.currentHealth > 0) {
                    return monster;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static ActionResult openChest() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        boolean bossRoom = room instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss;
        if (!bossRoom && !(room instanceof com.megacrit.cardcrawl.rooms.TreasureRoom)) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这里没有宝箱");
        }
        if (bossRoom) {
            BossChestGate.ensureChest();
        }
        com.megacrit.cardcrawl.rewards.chests.AbstractChest chest = bossRoom
                ? ((com.megacrit.cardcrawl.rooms.TreasureRoomBoss) room).chest
                : ((com.megacrit.cardcrawl.rooms.TreasureRoom) room).chest;
        if (chest == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "宝箱还没生成");
        }
        if (bossRoom && !BossChestGate.mustOpenChest()) {
            // Same predicate the planner uses to decide, so the two can never disagree: after a claim
            // (or a skip) the chest must NOT be reopened - that looped forever.
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                    "Boss 宝箱不需要再开（" + BossChestGate.describe() + "）");
        }
        // AbstractChest.isOpen is never set by open() itself (only the constructor and keyRequirement
        // touch it), so it cannot be used to detect "already opened". Vanilla relies on isScreenUp,
        // and every extra open() appends a whole new batch of relics to the reward list.
        if (chest.isOpen) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "宝箱已经开过了");
        }
        // The boss chest must be openable in the fade/screen=NONE frame too (the player arriving in
        // the boss room), so only a real boss-reward screen blocks it there.
        if (bossRoom) {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "Boss 遗物界面已经开着，先 claim_relic");
            }
        } else if (AbstractDungeon.isScreenUp) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "宝箱奖励界面已经开着");
        }
        if (room.rewards != null && !room.rewards.isEmpty()) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "宝箱已经开过了（奖励已生成）");
        }
        if (alreadyOpened.contains(chest)) {
            return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "宝箱已经开过了");
        }
        alreadyOpened.add(chest);
        chest.open(false);
        // Mark it ourselves so vanilla update() (which checks !isOpen) cannot open it again either.
        chest.isOpen = true;
        spirelink.util.Diag.info("open_chest: chest=" + chest.getClass().getSimpleName()
                + " isOpen=false → open()（并置 isOpen=true）");
        return ActionResult.ok();
    }

    /**
     * Every card of the card-reward screen as {@code "0=打击(Strike), 1=防御(Defend)"} (C7-04).
     *
     * <p>The same shape {@code take_reward} answers with (#101): the caller gets the legal range plus
     * the cards it may actually name, instead of a bare "牌序号越界: 9" it can only guess about.</p>
     */
    private static String cardRewardSummary(CardRewardScreen screen) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        if (screen != null && screen.rewardGroup != null) {
            for (int i = 0; i < screen.rewardGroup.size(); i++) {
                AbstractCard card = screen.rewardGroup.get(i);
                if (card == null) {
                    continue;
                }
                entries.add(IndexListText.entry(i, card.name, card.cardID));
            }
        }
        return "可拿: " + CardRewardPolicy.cardsText(entries);
    }

    /**
     * Every reward as {@code "0=金币(25 金), 1=遗物(小盒子)"} (issue #101): a rejection has to name a
     * usable index, otherwise the caller just repeats the same guess.
     */
    private static String takeableRewardSummary(CombatRewardScreen screen) {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        if (screen != null && screen.rewards != null) {
            for (int i = 0; i < screen.rewards.size(); i++) {
                RewardItem item = screen.rewards.get(i);
                if (item == null) {
                    continue;
                }
                String text = item.text == null ? String.valueOf(item.type)
                        : item.text.replaceAll("#.", "");
                String state = item.isDone ? "(已领)" : item.ignoreReward ? "(不可领)" : "";
                entries.add(i + "=" + text + state);
            }
        }
        return "可领取: " + TakeRewardRequest.listText(entries);
    }

    /**
     * The potion slots a full belt can free, as {@code "0=香蕉, 2=格挡药水"} (#116a).
     *
     * <p>{@code discard_potion} addresses a slot, so the rejection of "belt is full" has to say which
     * slots exist — the same shape {@code take_reward}/{@code take_card} use for indexes.</p>
     */
    private static String potionSlotsSummary() {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player != null && player.potions != null) {
                for (int slot = 0; slot < player.potions.size(); slot++) {
                    AbstractPotion potion = player.potions.get(slot);
                    if (spirelink.state.PotionSerializer.isPlaceholder(potion)
                            || !potion.canDiscard()) {
                        continue;
                    }
                    entries.add(IndexListText.entry(slot, potion.name));
                }
            }
        } catch (Throwable t) {
            return "（药水槽读不到: " + t + "）";
        }
        return IndexListText.list(entries, "（没有可以丢的药水）");
    }

    /**
     * Claims a key reward. Since 0.1.6 it also cuts the reward row it is linked to, exactly like
     * vanilla: the sapphire key and the chest's relic are an either/or choice.
     *
     * <p>#116b originally claimed the key <b>without</b> taking the linked row, because the vanilla
     * path made the relic disappear from the reward screen. That is the rule though - {@code
     * RewardItem.renderRelicLink} draws the chain precisely to say "one or the other" - and letting an
     * agent take both made every chest strictly better than the real game.</p>
     *
     * <p>Old #116b notes follow.</p>
     *
     * <p>Disassembled from {@code RewardItem.claimReward()}: the {@code SAPPHIRE_KEY} branch plays
     * {@code ObtainKeyEffect(BLUE)} and then does {@code relicLink.isDone = true;
     * relicLink.ignoreReward = true}; the {@code EMERALD_KEY} branch only plays the effect. In a real
     * chest ({@code AbstractChest.open()} -> {@code AbstractRoom.addSapphireKey(lastReward)}) the link
     * is the relic row of that same chest, so the vanilla path made the relic disappear from the reward
     * screen and from {@code player.relics}. The issue is explicit: taking the key must not swallow the
     * other rows.</p>
     *
     * <p>So the key's own effect is reproduced here (its last frame is what sets
     * {@code Settings.hasSapphireKey}/{@code hasEmeraldKey}), the row is marked done for our own
     * removal below, and the mutual {@code relicLink} is cut so the relic renders and claims as an
     * ordinary relic row afterwards. The key texture is deliberately not disposed: the item leaves both
     * reward lists in the same call, but a disposed texture that is drawn once more would throw, and the
     * leak is bounded (at most two keys in a run).</p>
     */
    private static void claimKeyReward(RewardItem reward) {
        boolean sapphire = reward.type == RewardItem.RewardType.SAPPHIRE_KEY;
        try {
            AbstractDungeon.topLevelEffects.add(new com.megacrit.cardcrawl.vfx.ObtainKeyEffect(
                    sapphire ? com.megacrit.cardcrawl.vfx.ObtainKeyEffect.KeyColor.BLUE
                            : com.megacrit.cardcrawl.vfx.ObtainKeyEffect.KeyColor.GREEN));
        } catch (Throwable t) {
            // The effect is also what flips the flag - without it the key would not count at all.
            if (sapphire) {
                com.megacrit.cardcrawl.core.Settings.hasSapphireKey = true;
            } else {
                com.megacrit.cardcrawl.core.Settings.hasEmeraldKey = true;
            }
            spirelink.util.Diag.warn("take_reward: 钥匙特效加不进去，直接置位: " + t);
        }
        RewardItem link = reward.relicLink;
        // 0.1.6: 还原原版。RewardItem.claimReward() 的 SAPPHIRE_KEY 分支在播完
        // ObtainKeyEffect(BLUE) 之后做的就是 relicLink.isDone = true; relicLink.ignoreReward = true;
        // ——宝箱里的钥匙和遗物是二选一（渲染时那条锁链就是 renderRelicLink）。
        // 之前 #116b 把链接切断、让两行都能拿，等于比原版多给一个奖励，测试的含金量就打折了。
        if (sapphire && link != null) {
            link.isDone = true;
            link.ignoreReward = true;
        }
        reward.isDone = true;
        spirelink.util.Diag.info("take_reward: " + (sapphire ? "SAPPHIRE_KEY" : "EMERALD_KEY")
                + " 已领取；连体奖励保留: "
                + (link == null ? "无" : String.valueOf(link.type)));
    }

    private static ActionResult takeReward(JsonObject payload) {
        CombatRewardScreen screen = AbstractDungeon.combatRewardScreen;
        if (screen == null || screen.rewards == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有奖励界面");
        }
        // Issue #101: the index is mandatory and is checked against the list. A missing or
        // out-of-range value used to be "repaired" by firstTakeableReward(), so an index of 42
        // silently claimed whatever happened to be first - in the reported run a relic the caller
        // never asked for. Answer BAD_REQUEST with the valid range and the actual items instead.
        TakeRewardRequest request;
        try {
            request = TakeRewardRequest.parse(payload);
        } catch (BadRequestException e) {
            // C8-02: the legal range belongs to *every* payload rejection, not only to an
            // out-of-range integer. A missing / non-integer / negative index answered with the item
            // list alone, so the caller still had no idea which numbers exist.
            return ActionResult.rejectParam(e.code, e.getMessage() + "；"
                    + TakeRewardRequest.rangeText(TakeRewardRequest.sizeOf(screen.rewards)) + "；"
                    + takeableRewardSummary(screen));
        }
        int index = request.index;
        if (!TakeRewardRequest.inRange(index, screen.rewards.size())) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    "payload." + request.key + "=" + index + " 越界："
                            + TakeRewardRequest.rangeText(screen.rewards.size()) + "；"
                            + takeableRewardSummary(screen));
        }
        RewardItem reward = screen.rewards.get(index);
        if (reward == null) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    "index=" + index + " 这条奖励不存在；" + takeableRewardSummary(screen));
        }
        if (reward.isDone) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    "index=" + index + " 这条奖励已经领过了；" + takeableRewardSummary(screen));
        }
        if (reward.ignoreReward) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    "index=" + index + " 这条奖励不可领取；" + takeableRewardSummary(screen));
        }
        if (reward.hb == null) {
            return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                    "index=" + index + " 这条奖励没有可点击的位置；"
                            + takeableRewardSummary(screen));
        }
        spirelink.util.Diag.info("take_reward: index=" + index + " item=" + reward.type
                + "/" + (reward.text == null ? "" : reward.text.replaceAll("#.", "")));
        // CombatRewardScreen.update() only removes a reward when claimReward() succeeds, and its
        // POTION branch resets isDone on failure while GOLD/RELIC/CARD do not - that is the "reward
        // screen never moves again" bug. So settle the reward ourselves and drop it from the list.
        AbstractPlayer player = AbstractDungeon.player;
        String type = String.valueOf(reward.type);
        try {
            if (reward.type == RewardItem.RewardType.GOLD) {
                int amount = reward.goldAmt + reward.bonusGold;
                if (player != null) {
                    player.gainGold(amount);
                }
            } else if (reward.type == RewardItem.RewardType.STOLEN_GOLD) {
                int amount = reward.goldAmt + reward.bonusGold;
                if (player != null) {
                    player.gainGold(amount);
                }
            } else if (reward.type == RewardItem.RewardType.POTION) {
                // 三种失败原因要分开：以前一律回「药水槽满了」，试玩 agent 看到的就是一条假话
                // （它明明有空槽，却被拒了 —— 实际是 reward.potion 为空/占位）。
                if (player == null) {
                    return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有玩家对象");
                }
                if (reward.potion == null) {
                    return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这条奖励没有药水对象");
                }
                if (!player.obtainPotion(reward.potion)) {
                    // #116a: "先丢掉一瓶才能拿" (the state's blocked_reason says exactly that) used to
                    // name an action this screen did not have - discard_potion was not in the
                    // combat_reward matrix and the planner never advertised it. It is a legal action
                    // here now, so the rejection points at it and lists the slots to name.
                    return ActionResult.reject(ErrorCodes.POTION_SLOT_FULL,
                            "药水槽满了，拿不了这瓶药水；先 discard_potion{\"slot\":N} 丢掉一瓶再回来拿"
                                    + "（可丢: " + potionSlotsSummary() + "）");
                }
            } else if (reward.type == RewardItem.RewardType.RELIC) {
                if (reward.relic == null) {
                    // Linked rewards ("take this relic, then pick a card") keep the real relic in
                    // relicLink; calling instantObtain() on the empty slot is what re-ran the vanilla
                    // reward path and kept adding new relic rewards.
                    if (reward.relicLink != null) {
                        reward.isDone = true;
                        reward.ignoreReward = true;
                        spirelink.util.Diag.info("take_reward: index=" + index
                                + " type=RELIC 是 relicLink 连体奖励，交回游戏处理");
                        return ActionResult.ok();
                    }
                    return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "这条遗物奖励是空的");
                }
                reward.relic.instantObtain();
            } else if (reward.type == RewardItem.RewardType.SAPPHIRE_KEY
                    || reward.type == RewardItem.RewardType.EMERALD_KEY) {
                // #116b: vanilla's key branch is a *linked* claim (RewardItem.claimReward(),
                // disassembled: SAPPHIRE_KEY -> ObtainKeyEffect(BLUE) and relicLink.isDone=true;
                // relicLink.ignoreReward=true). In a real chest relicLink is the relic row of that same
                // chest, so taking the key made the relic vanish from the screen and from
                // player.relics - and reward rows must never disappear behind the caller's back.
                claimKeyReward(reward);
            } else if (reward.type == RewardItem.RewardType.CARD) {
                // Card rewards open their own screen; letting the game do it keeps links/BASE_MOD
                // hooks working, so just mark it done for this frame.
                reward.isDone = true;
                spirelink.util.Diag.info("take_reward: index=" + index + " type=CARD 交给卡牌界面");
                return ActionResult.ok();
            } else {
                boolean done = reward.claimReward();
                spirelink.util.Diag.info("take_reward: index=" + index + " type=" + type
                        + " claimReward=" + done);
                if (!done) {
                    return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION,
                            "这条奖励游戏自己领不了: " + type);
                }
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("take_reward", t);
            return ActionResult.reject(ErrorCodes.NOT_READY, "领取奖励失败: " + t);
        }
        // Remove the exact object (indexes shift when the planner re-reads) from both the screen list
        // and the room's copy: leaving it in the room let a re-opened screen duplicate every reward.
        int screenBefore = screen.rewards.size();
        boolean removed = screen.rewards.remove(reward);
        int roomBefore = -1;
        int roomAfter = -1;
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null && room.rewards != null) {
                roomBefore = room.rewards.size();
                room.rewards.remove(reward);
                roomAfter = room.rewards.size();
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("take_reward: 清理房间奖励列表失败: " + t);
        }
        spirelink.util.Diag.info("take_reward: index=" + index + " type=" + type
                + " removed=" + removed + " screen " + screenBefore + "->" + screen.rewards.size()
                + " room " + roomBefore + "->" + roomAfter);
        return ActionResult.ok();
    }

    /**
     * Drops every reward still on the reward screen and returns how many were given up.
     *
     * <p>The room keeps its own copy of the list; if it survives, the next frame's
     * {@code CombatRewardScreen.open()} re-adds the whole list (this is how the reward list grew to 32
     * entries), so both copies go.</p>
     */
    private static int dropRewardLists() {
        int dropped = 0;
        try {
            CombatRewardScreen screen = AbstractDungeon.combatRewardScreen;
            if (screen != null && screen.rewards != null) {
                dropped = screen.rewards.size();
                screen.rewards.clear();
            }
            AbstractRoom room = currRoomOrNull();
            if (room != null && room.rewards != null) {
                dropped = Math.max(dropped, room.rewards.size());
                room.rewards.clear();
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("proceed: 清空奖励列表失败: " + t);
        }
        return dropped;
    }

    /**
     * #114 (S1): {@code proceed} on the act-boss reward screen.
     *
     * <p>Vanilla's {@code ProceedButton.update()} runs the boss hand-over here - {@code goToTreasureRoom}
     * for acts 1/2 - and the boss relic only exists behind that chest. {@link BossRewardPolicy} owns the
     * choice; this method owns the order: the transition is attempted <b>first</b>, so a failure cannot
     * both eat the remaining rewards and leave the agent on the same screen. When it cannot be run the
     * answer is an explicit rejection with a reason (#114: "做不到就明确拒绝 proceed 并说明原因，绝不能留下
     * 死地图"), and #118: it is never a bare {@code ok}.</p>
     */
    private static ActionResult bossRewardProceed(AbstractRoom room) {
        com.megacrit.cardcrawl.ui.buttons.ProceedButton button =
                AbstractDungeon.overlayMenu == null ? null
                        : AbstractDungeon.overlayMenu.proceedButton;
        if (button == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    BossRewardPolicy.handOverFailedHint(room.getClass().getSimpleName()));
        }
        ActionResult moved = handOverOrExplain(button, room);
        if (!moved.ok) {
            // Nothing was dropped: an action that does not move the run must not eat rewards either.
            return moved;
        }
        int dropped = dropRewardLists();
        spirelink.util.Diag.info("proceed: Boss 奖励屏放弃 " + dropped + " 条剩余奖励"
                + "（Boss 遗物在 Boss 宝箱房里，别开地图）");
        return moved;
    }

    /** Opens the act map the same way the overlay proceed button does. Idempotent. */
    private static boolean openMapScreen() {
        try {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                return true;
            }
            if (AbstractDungeon.dungeonMapScreen == null) {
                return false;
            }
            AbstractDungeon.dungeonMapScreen.open(false);
            AbstractDungeon.screen = AbstractDungeon.CurrentScreen.MAP;
            AbstractDungeon.isScreenUp = true;
            return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP;
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("openMapScreen", t);
            return false;
        }
    }

    private static ActionResult proceed() {
        // 0.1.7 硬约束：拿到 pending 就说明这一局刚结束、复盘还没写。
        java.util.List<String> pendingNotes = spirelink.state.NotesGate.pending();
        if (!pendingNotes.isEmpty()) {
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "这局已经结束，先写复盘便签再开新局：" + pendingNotes
                            + "（note_set{kind:\"monster\", id:\"<怪物 id>\", text:\"下次怎么打\"} 与 "
                            + "note_set{kind:\"general\", id:\"<任意>\", text:\"下次遇到这种情况怎么办\"}）");
        }
        // Issue #70: on the main menu there is no currMapNode, so getCurrRoom() throws an NPE that
        // used to surface as INTERNAL. The legality matrix already rejects proceed there; this is the
        // second layer, so no future caller can bring the INTERNAL NPE back.
        if (currRoomOrNull() == null) {
            return ActionResult.reject(CODE_ILLEGAL,
                    "现在没有进行中的房间（还没有进入对局），proceed 没有可继续的对象");
        }
        // Applies to EVERY proceed branch below (including the generic button click): leaving the act
        // boss treasure room without the relic destroys it, and the click-only path used to slip past
        // that check.
        if (BossChestGate.mustOpenChest()) {
            BossChestGate.ensureChest();
            return ActionResult.reject(ErrorCodes.NOT_READY,
                    "先 open_chest 开 Boss 宝箱并 claim_relic，现在走会丢遗物（"
                            + BossChestGate.describe() + "）");
        }
        // Issue #100 (S1): while the dungeon map is up, proceed must act on the MAP and on nothing
        // else. It used to fall through to the room branches below: for a finished rest/shop/event
        // room the "click the overlay proceed button" branch matched FIRST, and that button does
        // nothing while the map owns the screen. The call answered ok, the state signature never
        // moved, and the harness reported "queued" after 10.6 s and then refused every following
        // act for 13 s (ACTION_IN_FLIGHT). The map decision is the same pure one the planner uses.
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
            return mapProceed();
        }
        // Reward screens: "proceed" means "give up what is left and leave". Rewards that cannot be
        // claimed at all (a potion with a full belt) would otherwise leave the agent with no exit.
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
            AbstractRoom rewardRoom = currRoomOrNull();
            // #114 (S1): an act-boss reward screen is the ONE place the boss relic can still be saved,
            // and it is not saved on the map. This branch used to clear the list, close the screen and
            // open the map for every room - after a boss that skipped BossRelicSelectScreen for good and
            // left the exhausted act map up (reachable=[] -> abandon_mid_run). Same helper the
            // room-complete branch uses, so "what does proceed do to a boss room" has one answer.
            if (rewardRoom != null
                    && BossRewardPolicy.bossFightRoom(rewardRoom.getClass().getSimpleName())) {
                return bossRewardProceed(rewardRoom);
            }
            try {
                int dropped = dropRewardLists();
                AbstractDungeon.closeCurrentScreen();
                // One proceed should be enough: close the reward screen *and* show the map, instead of
                // leaving the agent in ui=idle with a second "退出当前画面".
                boolean mapOpened = openMapScreen();
                spirelink.util.Diag.info("proceed: 放弃 " + dropped + " 条剩余奖励并关闭奖励界面"
                        + (mapOpened ? "，已打开地图" : "（地图稍后再开）"));
                return mapOpened ? ActionResult.verified() : ActionResult.ok();
            } catch (Throwable t) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "关闭奖励界面失败: " + t);
            }
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
            // C7-01: act{proceed} on card_reward is no longer reachable — the legality matrix lists
            // only take_card/skip_card for that ui, so the gate answers ILLEGAL_ACTION. The two
            // operations are the same one (skip the pick), and this branch keeps that true for any
            // internal caller instead of letting a second "close the screen" path grow here.
            return skipCard();
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD) {
            try {
                Object screen = AbstractDungeon.bossRelicScreen;
                if (screen == null) {
                    return ActionResult.reject(ErrorCodes.NOT_READY, "现在没有遗物选择界面");
                }
                AbstractDungeon.closeCurrentScreen();
                spirelink.util.Diag.info("proceed: 放弃 BOSS 遗物选择并关闭界面");
                return ActionResult.ok();
            } catch (Throwable t) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "关闭遗物界面失败: " + t);
            }
        }
        // Combat rooms whose reward screen is already closed (ui=idle, phase=COMPLETE) are exited with
        // the same overlay proceed button. Without this the agent had exactly one action - proceed -
        // and it answered "当前没有事件对话框", which is both wrong and unrecoverable.
        {
            AbstractRoom room = currRoomOrNull();
            boolean combatRoom = room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom
                    || room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomElite
                    || room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss
                    || room instanceof com.megacrit.cardcrawl.rooms.VictoryRoom;
            if (room != null && room.phase == AbstractRoom.RoomPhase.COMPLETE
                    && (combatRoom || AbstractDungeon.screen == null
                            || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE)) {
                if (AbstractDungeon.overlayMenu != null
                        && AbstractDungeon.overlayMenu.proceedButton != null) {
                    com.megacrit.cardcrawl.ui.buttons.ProceedButton button =
                            AbstractDungeon.overlayMenu.proceedButton;
                    // Boss/victory rooms: clicking is unreliable (show() only starts an animation and
                    // hb.update() re-derives hovered from the real mouse, so a virtual click is eaten).
                    // Call the transition the button would call instead.
                    if (room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss
                            || room instanceof com.megacrit.cardcrawl.rooms.VictoryRoom
                            || room instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss) {
                        // TreasureRoomBoss included: its overlay click is just as unreliable, and the
                        // transition it wants (goToNextDungeon) is deterministic.
                        // #118 (S1): one answer for "move the run past this boss room" - it runs the
                        // transition or rejects with the reason, and it never answers ok without
                        // starting one (that silence is what froze the run at ui=treasure).
                        return handOverOrExplain(button, room);
                    }
                    // Plain rooms: the overlay click only *shows* the button and its hitbox is
                    // re-derived every frame, so the agent needed two proceeds to reach the map.
                    // Opening the map is the transition that click would trigger.
                    if (openMapScreen()) {
                        spirelink.util.Diag.info("proceed: 战斗结束继续（"
                                + room.getClass().getSimpleName()
                                + "，phase=COMPLETE）→ 直接打开地图（screen="
                                + AbstractDungeon.screen + "）");
                        return ActionResult.verified();
                    }
                    button.show();
                    Hitbox hitbox = (Hitbox) spirelink.util.FieldKit.fieldOrNull(button, "hb");
                    if (hitbox == null) {
                        return ActionResult.reject(ErrorCodes.NOT_READY, "继续按钮的 hitbox 读不到");
                    }
                    hitbox.clicked = true;
                    spirelink.util.Diag.info("proceed: 战斗结束继续（"
                            + room.getClass().getSimpleName() + "，phase=COMPLETE）show()+hb.clicked");
                    return ActionResult.ok();
                }
                return ActionResult.reject(ErrorCodes.NOT_READY, "继续按钮还没创建");
            }
        }
        // Room-scoped "continue": rest/shop/treasure/event all end by clicking the overlay proceed
        // button (CommunicationMod's clickProceedButton). The old code always tried "next room",
        // which only works for monster rooms and rejected everything else with 当前没有可进入的房间.
        {
            AbstractRoom current = currRoomOrNull();
            if (spirelink.events.MatchGame.active()) {
                // The minigame has no overlay button of its own: leave through the event's openMap().
                if (spirelink.events.MatchGame.leave()) {
                    return ActionResult.ok();
                }
                return ActionResult.reject(ErrorCodes.NOT_READY, "翻牌小游戏退出失败");
            }
            if (current instanceof com.megacrit.cardcrawl.rooms.RestRoom
                    || current instanceof com.megacrit.cardcrawl.rooms.ShopRoom
                    || current instanceof com.megacrit.cardcrawl.rooms.TreasureRoom
                    || current instanceof com.megacrit.cardcrawl.rooms.EventRoom) {
                if (AbstractDungeon.overlayMenu == null
                        || AbstractDungeon.overlayMenu.proceedButton == null) {
                    return ActionResult.reject(ErrorCodes.NOT_READY, "继续按钮不可用");
                }
                // Vanilla marks the room complete before the proceed button becomes usable; without
                // this the map screen keeps the room at INCOMPLETE and the boss click never fires.
                if (current.phase != null && current.phase != AbstractRoom.RoomPhase.COMPLETE
                        && current.phase != AbstractRoom.RoomPhase.COMBAT) {
                    current.phase = AbstractRoom.RoomPhase.COMPLETE;
                }
                com.megacrit.cardcrawl.ui.buttons.ProceedButton button =
                        AbstractDungeon.overlayMenu.proceedButton;
                button.show();
                Hitbox hitbox = (Hitbox) spirelink.util.FieldKit.viaBasemod(button,
                        com.megacrit.cardcrawl.ui.buttons.ProceedButton.class, "hb");
                if (hitbox == null) {
                    return ActionResult.reject(ErrorCodes.NOT_READY, "读不到继续按钮的 hitbox");
                }
                hitbox.clicked = true;
                spirelink.util.Diag.info("proceed: 房间退出按钮（"
                        + current.getClass().getSimpleName() + "，phase=" + current.phase
                        + "）show() + hb.clicked");
                return ActionResult.ok();
            }
        }
        // (The map screen used to be handled here, after the room branches - that ordering is exactly
        // what made proceed answer ok without closing anything (#100). It now runs at the top of
        // this method, before any room branch can match: see mapProceed().)
        // Universal exit (I4) for screens we have no dedicated flow for: close the screen, else
        // fall through to the overlay proceed button.
        if (AbstractDungeon.screen != null
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.COMBAT_REWARD
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.DEATH
                && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.VICTORY) {
            try {
                AbstractDungeon.closeCurrentScreen();
                spirelink.util.Diag.info("proceed: 通用退出 closeCurrentScreen()");
                return ActionResult.ok();
            } catch (Throwable ignored) {
                // fall through to the overlay button
            }
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH
                || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY
                || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CREDITS
                || currRoomOrNull()
                        instanceof com.megacrit.cardcrawl.rooms.VictoryRoom) {
            // Victory is a room first and a screen second: on the winning frame screen is still NONE.
            return runOverProceed();
        }
        // Reward screen: proceed is the overlay button, and only after the rewards are settled.
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
            CombatRewardScreen screen = AbstractDungeon.combatRewardScreen;
            if (screen != null && screen.rewards != null && !screen.hasTakenAll) {
                for (RewardItem reward : screen.rewards) {
                    if (reward != null && !reward.isDone) {
                        return ActionResult.reject(ErrorCodes.ILLEGAL_ACTION, "还有奖励没领");
                    }
                }
            }
            if (AbstractDungeon.overlayMenu == null
                    || AbstractDungeon.overlayMenu.proceedButton == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "继续按钮不可用");
            }
            // ProceedButton keeps its hitbox private; BaseMod's helper reads it without reflection
            // boilerplate here.
            Hitbox hitbox = ReflectionHacks.getPrivate(AbstractDungeon.overlayMenu.proceedButton,
                    com.megacrit.cardcrawl.ui.buttons.ProceedButton.class, "hb");
            if (hitbox == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "继续按钮不可用");
            }
            hitbox.clicked = true;
            spirelink.util.Diag.info("proceed: 点奖励界面的继续按钮");
            return ActionResult.ok();
        }
        List<LargeDialogOptionButton> options = ActionPlanner.dialogOptions();
        if (options == null) {
            // Nothing left to continue: say where we actually are instead of blaming the event dialog.
            AbstractRoom room = currRoomOrNull();
            return ActionResult.reject(CODE_ILLEGAL, "当前没有可继续的对象（ui="
                    + AbstractDungeon.screen + "，room="
                    + (room == null ? "null" : room.getClass().getSimpleName())
                    + "，phase=" + (room == null ? "null" : room.phase) + "）");
        }
        if (options.size() != 1) {
            return ActionResult.reject(CODE_ILLEGAL, "还有 " + options.size() + " 个选项要选");
        }
        LargeDialogOptionButton option = options.get(0);
        if (option == null || option.hb == null) {
            return ActionResult.reject(CODE_ILLEGAL, "继续按钮不可用");
        }
        option.hb.clicked = true;
        return ActionResult.ok();
    }

    /**
     * {@code proceed} while the dungeon map is open (issue #100).
     *
     * <p>The map is a modal screen with exactly one real exit: closing it while the current room is
     * still unfinished. Everything else is a refusal that names a usable alternative, because the old
     * behaviour - answering ok and letting the call sit in the queue until the harness gave up - is
     * the soft lock QA filed as S1.</p>
     *
     * <p>Both the advertisement condition and this acceptance condition come from
     * {@link MapExitPolicy#decide}.</p>
     */
    private static ActionResult mapProceed() {
        if (AbstractDungeon.dungeonMapScreen == null) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "地图界面还没准备好");
        }
        AbstractRoom room = currRoomOrNull();
        boolean canReturnToRoom = room != null
                && room.phase != AbstractRoom.RoomPhase.COMPLETE;
        int reachable = ActionPlanner.reachableNodes().size();
        boolean boss = ActionPlanner.bossAvailable();
        int decision = MapExitPolicy.decide(reachable, boss, canReturnToRoom);
        int bossY = AbstractDungeon.map == null ? 0 : AbstractDungeon.map.size();
        if (!MapExitPolicy.proceedClosesMap(decision)) {
            String message = MapExitPolicy.message(decision, reachable, bossY);
            if (decision == MapExitPolicy.DEAD_END) {
                spirelink.util.Diag.warn("proceed: 地图无解，明确拒绝（#100）: " + message + " | "
                        + ActionPlanner.mapDump());
            } else {
                spirelink.util.Diag.info("proceed: 地图上先走节点/Boss（#100）: " + message);
            }
            return ActionResult.reject(CODE_ILLEGAL, message);
        }
        try {
            AbstractDungeon.dungeonMapScreen.close();
            AbstractDungeon.closeCurrentScreen();
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "地图界面没关掉，稍后重试");
            }
            spirelink.util.Diag.info("proceed: 关闭地图（dungeonMapScreen.close + closeCurrentScreen）");
            return ActionResult.verified();
        } catch (Throwable t) {
            return ActionResult.reject(ErrorCodes.NOT_READY, "关闭地图失败: " + t);
        }
    }

    // ---------------------------------------------------------------- map

    /**
     * {@code choose_map_node} — <b>payload first, room phase second</b> (#110).
     *
     * <p>The report: {@code {"x": "<字符串>", "y": 3}} answered
     * {@code NOT_READY "房间还没结束（phase=…）"} followed by a tip telling the caller to proceed
     * first. The payload was read with {@code num()}, whose fallback is {@code -1} — exactly the Boss
     * sentinel — so a typo was read as
     * "walk to the Boss", answered with a room-phase sentence, and counted as a type-level refusal
     * (which is what printed the false {@code planner/executor 不一致} WARN). A payload problem gets a
     * payload answer: {@link MapNodeRequest} validates first, and every rejection that is about the
     * <i>request</i> is {@code rejectParam} — a malformed field is {@code BAD_REQUEST}, and a
     * well-formed payload that is simply not advertised this tick (Boss entry while the room is
     * unfinished) is still parameter level, so it cannot be mistaken for a planner/executor
     * disagreement. Only the two pure state refusals stay type level (no map screen, no room at all).
     * The room-phase sentence may also only name actions that really run this tick
     * ({@link MapExitPolicy#executableHint}), never the reported tip that pointed at a move which was
     * not advertised at all.</p>
     */
    private static ActionResult chooseMapNode(JsonObject payload) {
        MapNodeRequest request;
        try {
            request = MapNodeRequest.parse(payload);
        } catch (BadRequestException e) {
            // No room-phase wording here on purpose: the request is wrong whatever the room is doing,
            // and the planner did advertise choose_map_node, so this is param level, not 「不一致」.
            return ActionResult.rejectParam(e.code, e.getMessage());
        }
        if (AbstractDungeon.dungeonMapScreen == null) {
            return ActionResult.reject(CODE_ILLEGAL, "地图界面还没准备好");
        }
        int rows = AbstractDungeon.map == null ? -1 : AbstractDungeon.map.size();
        if (request.hasInvalidRow()) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    MapNodeRequest.rowMessage(request.y, rows));
        }
        if (request.hasInvalidColumn()) {
            return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                    MapNodeRequest.columnMessage(request.x, rows));
        }
        if (request.isBossEntry()) {
            // Boss entry: the planner signals it with x=-1, y=rows because the boss has no node.
            // DungeonMap.update() only reacts when the room is COMPLETE, the map is open and the
            // current node is the last row, so verify all three and say which one is missing.
            if (rows > 0 && !request.isBossRow(rows)) {
                return ActionResult.rejectParam(ErrorCodes.BAD_REQUEST,
                        MapNodeRequest.bossRowMessage(request.y, rows));
            }
            AbstractRoom room = currRoomOrNull();
            if (room == null) {
                return ActionResult.reject(ErrorCodes.NOT_READY, "当前没有房间");
            }
            if (room.phase != AbstractRoom.RoomPhase.COMPLETE) {
                // #110: the hint comes from MapExitPolicy — the same decision the planner advertises
                // from — so it can only name an action this tick can really run. The old sentence
                // told the caller to proceed first even on a map that offered nodes and therefore
                // never advertised proceed.
                //
                // Param level on purpose: the Boss payload is simply not among the payloads the
                // planner advertises right now (it advertises nodes), so answering with a type-level
                // refusal is what printed the reported "planner/executor 不一致" WARN.
                return ActionResult.rejectParam(ErrorCodes.NOT_READY,
                        "房间还没结束（phase=" + room.phase + "），Boss 图标现在点不了"
                                + (rows > 0 ? "（" + MapNodeRequest.bossEntryText(rows) + "）" : "")
                                + "；" + MapExitPolicy.executableHint(mapDecision()));
            }
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
                return ActionResult.reject(ErrorCodes.NOT_READY,
                        "地图没有打开（screen=" + AbstractDungeon.screen + "）");
            }
            MapRoomNode current = AbstractDungeon.getCurrMapNode();
            int lastRow = AbstractDungeon.map == null ? -1 : AbstractDungeon.map.size() - 1;
            if (current == null || current.y != lastRow) {
                // Same reasoning as above: only the last row advertises the Boss payload.
                return ActionResult.rejectParam(ErrorCodes.NOT_READY,
                        "当前不在末行（cur=" + (current == null ? "null" : current.y)
                                + "，末行=" + lastRow + "），Boss 图标还点不了；"
                                + MapExitPolicy.executableHint(mapDecision()));
            }
            spirelink.patches.BossClickPatch.request();
            spirelink.util.Diag.info("choose_map_node: 请求点击 Boss 图标（末行 " + lastRow + "）");
            return ActionResult.ok();
        }
        for (MapRoomNode node : ActionPlanner.reachableNodes()) {
            if (node.x == request.x && node.y == request.y) {
                return travelTo(node);
            }
        }
        // A node that is not among the advertised ones is a payload problem, so answer with the nodes
        // that can be named instead of a bare 「现在不可达」. The code stays ILLEGAL_ACTION:
        // docs/MCP-返回格式.md §6.2 documents exactly that for a changed x/y.
        return ActionResult.rejectParam(CODE_ILLEGAL,
                "节点 (" + request.x + "," + request.y + ") 现在不可达；" + reachableSummary());
    }

    /** What the map can still do this tick — the same three inputs the planner's map() decides with. */
    private static int mapDecision() {
        AbstractRoom room = currRoomOrNull();
        boolean canReturnToRoom = room != null && room.phase != AbstractRoom.RoomPhase.COMPLETE;
        return MapExitPolicy.decide(ActionPlanner.reachableNodes().size(),
                ActionPlanner.bossAvailable(), canReturnToRoom);
    }

    /** "(3,2), (5,4)" — the nodes that can be walked to right now (C8-01: list the candidates). */
    private static String reachableSummary() {
        java.util.List<String> entries = new java.util.ArrayList<String>();
        for (MapRoomNode node : ActionPlanner.reachableNodes()) {
            entries.add("(" + node.x + "," + node.y + ")");
        }
        if (entries.isEmpty()) {
            return "现在没有能走的节点（" + MapExitPolicy.executableHint(mapDecision()) + "）";
        }
        return "现在能走的节点: " + IndexListText.list(entries, "（没有）");
    }

    /**
     * Performs the travel sequence the vanilla click branch runs once its hover/animation checks
     * pass (12-18-2022 {@code MapRoomNode.update} bytecode): mark the current node taken, publish
     * {@code AbstractDungeon.nextRoom} and start the transition. No mouse, no hover, no patch, so it
     * cannot be lost to a frame-timing race.
     */
    private static ActionResult travelTo(MapRoomNode node) {
        try {
            MapRoomNode curr = AbstractDungeon.getCurrMapNode();
            // Hard re-validation: the planner's answer can be stale, and moving to a node with no
            // edge to the current one wedges the room transition (the game stops rendering).
            boolean reachable;
            // A node with no outgoing edge is a dead end: entering it clears the room and then no
            // next node is reachable, which strands the run. The last row is exempt (it leads to the
            // boss / next act).
            int lastRow = AbstractDungeon.map == null ? -1 : AbstractDungeon.map.size() - 1;
            boolean bossNode = node.room instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
            if (ActionPlanner.edgesOf(node) == 0 && node.y < lastRow && !bossNode) {
                spirelink.util.Diag.warn("拒绝位移: 目标节点没有出边（会是死局） (" + node.x + ","
                        + node.y + ") " + ActionPlanner.mapDump());
                return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION,
                        "该节点没有下一层，会卡死");
            }
            if (!AbstractDungeon.firstRoomChosen) {
                reachable = node.y == 0;
            } else if (curr == null) {
                reachable = false;
            } else {
                reachable = curr.isConnectedTo(node) || curr.wingedIsConnectedTo(node);
            }
            if (!reachable) {
                spirelink.util.Diag.warn("拒绝位移: 节点不可达 curr="
                        + (curr == null ? "null" : "(" + curr.x + "," + curr.y + ")")
                        + " 目标=(" + node.x + "," + node.y + ") " + ActionPlanner.mapDump());
                return ActionResult.rejectParam(ErrorCodes.ILLEGAL_ACTION, "节点不可达");
            }
            boolean beforeFirstRoomChosen = AbstractDungeon.firstRoomChosen;
            String beforeNode = curr == null ? "null" : "(" + curr.x + "," + curr.y + ")";
            if (!AbstractDungeon.firstRoomChosen) {
                AbstractDungeon.setCurrMapNode(node);
                // Vanilla sets this in MapRoomNode.update()'s click branch; without it every
                // later move keeps treating the bottom row as "reachable" (y always 0).
                AbstractDungeon.firstRoomChosen = true;
            } else if (curr != null) {
                MapEdge edge = curr.getEdgeConnectedTo(node);
                if (edge != null) {
                    edge.markAsTaken();
                }
                curr.taken = true;
            }
            node.taken = true;
            AbstractDungeon.nextRoom = node;
            // One path entry per step: repeat clicks on the same node must not duplicate it.
            if (AbstractDungeon.pathY.isEmpty()
                    || AbstractDungeon.pathY.get(AbstractDungeon.pathY.size() - 1).intValue()
                    != node.y
                    || AbstractDungeon.pathX.isEmpty()
                    || AbstractDungeon.pathX.get(AbstractDungeon.pathX.size() - 1).intValue()
                    != node.x) {
                AbstractDungeon.pathX.add(Integer.valueOf(node.x));
                AbstractDungeon.pathY.add(Integer.valueOf(node.y));
            }
            if (CardCrawlGame.metricData != null && CardCrawlGame.metricData.path_taken != null) {
                AbstractRoom room = node.getRoom();
                CardCrawlGame.metricData.path_taken.add(room == null ? "?" : room.getMapSymbol());
            }
            AbstractDungeon.nextRoomTransitionStart();
            spirelink.util.Diag.info("choose_map_node: 目标=(" + node.x + "," + node.y + ")"
                    + " firstRoomChosen " + beforeFirstRoomChosen + "->"
                    + AbstractDungeon.firstRoomChosen
                    + " currNode " + beforeNode + "->(" + node.x + "," + node.y + ")"
                    + " pathY=" + AbstractDungeon.pathY);
            return ActionResult.ok();
        } catch (Throwable t) {
            return ActionResult.reject(CODE_ILLEGAL, "位移失败: " + t);
        }
    }

    // ---------------------------------------------------------------- legality gate

    /**
     * Issues #57 / #70: consult {@link ActionLegality} before the action runs.
     *
     * @return {@code null} when the action may run, otherwise the rejection to hand back
     */
    static ActionResult gate(String type) {
        if (type == null) {
            // executeInner answers "缺少动作类型" (ILLEGAL_ACTION) — keep that one behaviour.
            return null;
        }
        // B2-12: a type the mod does not implement is not "illegal in this ui", it is unknown. The
        // docs/samples promise UNKNOWN_ACTION_TYPE and the catch-all dispatch used to answer
        // ILLEGAL_ACTION instead.
        String unknown = ActionLegality.unknownTypeCode(type);
        if (unknown != null) {
            return ActionResult.reject(unknown,
                    "不认识的动作类型: " + type + "（动作名见 get_state 的 legal_actions）");
        }
        String ui;
        try {
            ui = ActionLegality.uiOf(facts());
        } catch (Throwable t) {
            // A broken screen probe must never take an action away.
            spirelink.util.Diag.warn("act 合法性检查读状态失败，放行: " + t);
            return null;
        }
        String reason = ActionLegality.check(ui, type);
        if (reason != null) {
            // Issue #91: the "可用" list has to be what the planner is offering right now, not the
            // static matrix (which listed actions that are not on the table, e.g. back on main_menu).
            String withAdvertised = ActionLegality.check(ui, type, advertisedTypes(ui));
            if (withAdvertised != null) {
                reason = withAdvertised;
            }
        } else {
            // Issue #92: treasure room — proceed before open_chest silently eats the chest reward.
            String stateReason = ActionLegality.treasureRule(ui, type, treasureChestNeedsOpening());
            if (stateReason == null) {
                return null;
            }
            reason = stateReason;
        }
        spirelink.util.Diag.warn("act 被合法性矩阵拒绝: " + reason);
        return ActionResult.reject(ActionLegality.violationCode(ui, type),
                "动作不合法: " + reason);
    }

    /**
     * Action types {@link ActionPlanner} is advertising for {@code ui} right now, in order and
     * without duplicates (#91). {@code null} when it cannot be read, so the caller falls back to the
     * matrix.
     */
    static java.util.List<String> advertisedTypes(String ui) {
        try {
            java.util.List<ActionSpec> specs = ActionPlanner.actionsFor(ui);
            if (specs == null) {
                return null;
            }
            java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<String>();
            for (ActionSpec spec : specs) {
                if (spec != null && spec.type != null) {
                    types.add(spec.type);
                }
            }
            return new java.util.ArrayList<String>(types);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Issue #92: is the treasure chest still untouched (so {@code proceed} would throw the reward
     * away)?
     *
     * <p>Exactly {@code ActionPlanner#treasure}'s condition for advertising {@code open_chest}: the
     * chest exists, is not open, no screen is up and the room holds no rewards yet. Keeping the two
     * in lockstep is what stops the gate from rejecting an action the planner just advertised.</p>
     *
     * <p>{@code false} for every room that is not a plain {@code TreasureRoom} (the act-boss
     * {@code TreasureRoomBoss} has its own {@code BossChestGate}) and for a broken probe — a failed
     * read must never take an action away.</p>
     */
    static boolean treasureChestNeedsOpening() {
        try {
            AbstractRoom room = currRoomOrNull();
            if (!(room instanceof com.megacrit.cardcrawl.rooms.TreasureRoom)) {
                return false;
            }
            com.megacrit.cardcrawl.rewards.chests.AbstractChest chest =
                    ((com.megacrit.cardcrawl.rooms.TreasureRoom) room).chest;
            if (chest == null || chest.isOpen) {
                return false;
            }
            boolean rewardsPending = room.rewards != null && !room.rewards.isEmpty();
            return !AbstractDungeon.isScreenUp && !rewardsPending;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Snapshot of everything {@link ActionLegality#uiOf} needs. Never throws. */
    static ActionLegality.Facts facts() {
        boolean tutorialTip = false;
        boolean inGame = false;
        boolean victoryScreen = false;
        String menuScreen = null;
        String screen = null;
        String roomClass = null;
        String roomPhase = null;
        try {
            tutorialTip = AbstractDungeon.ftue != null
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.FTUE;
        } catch (Throwable ignored) {
            // not ready yet
        }
        try {
            inGame = CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY
                    && AbstractDungeon.player != null;
        } catch (Throwable ignored) {
            // ditto
        }
        try {
            MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
            menuScreen = menu == null || menu.screen == null ? null : menu.screen.name();
        } catch (Throwable ignored) {
            // ditto
        }
        try {
            screen = AbstractDungeon.screen == null ? null : AbstractDungeon.screen.name();
        } catch (Throwable ignored) {
            // ditto
        }
        try {
            AbstractRoom room = currRoomOrNull();
            roomClass = room == null ? null : room.getClass().getSimpleName();
            roomPhase = room == null || room.phase == null ? null : room.phase.name();
        } catch (Throwable ignored) {
            // ditto
        }
        try {
            victoryScreen = AbstractDungeon.victoryScreen != null;
        } catch (Throwable ignored) {
            // ditto
        }
        return new ActionLegality.Facts(tutorialTip, inGame, menuScreen, screen, roomClass,
                roomPhase, victoryScreen);
    }

    /**
     * {@code AbstractDungeon.getCurrRoom()} dereferences {@code currMapNode} (AbstractDungeon:809)
     * and throws a NullPointerException on the main menu — exactly the NPE issue #70 reported.
     *
     * @return the current room, or {@code null} when there is none
     */
    static AbstractRoom currRoomOrNull() {
        try {
            if (AbstractDungeon.currMapNode == null) {
                return null;
            }
            return AbstractDungeon.getCurrRoom();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String str(JsonObject payload, String key, String fallback) {
        if (payload == null || !payload.has(key) || !payload.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return payload.get(key).getAsString();
    }

    /**
     * Legacy coercing reader: {@code getAsInt()} reads the string {@code "3"} as 3, <b>truncates</b>
     * {@code 1.5} to 1, and silently falls back to {@code fallback} when the value is unreadable.
     * That is exactly how #110 (a typo became {@code -1}, the Boss sentinel) and #111
     * ({@code take_card{index:1.5}} took card 1) happened.
     *
     * <p>Do <b>not</b> use it for a payload field: {@link IntArgument} refuses a non-integer instead
     * of guessing. The remaining call sites here are actions that have not been audited yet.</p>
     */
    private static int num(JsonObject payload, String key, int fallback) {
        if (payload == null || !payload.has(key) || !payload.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return payload.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
