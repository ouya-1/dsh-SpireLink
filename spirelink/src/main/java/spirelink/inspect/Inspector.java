package spirelink.inspect;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.TipHelper;

import java.util.ArrayList;

/**
 * Design 9 "gaze": make the game believe the mouse is hovering the card the agent is inspecting.
 *
 * <p>Hand cards use the engine's own hover animation (InputHelper coordinates are overridden in
 * {@code updateFirst}, so lift/scale/tips all run vanilla). Cards outside the hand have no hover
 * animation, so those fall back to a side panel drawing.
 */
public final class Inspector {

    private static final long MAX_DURATION_MS = 10000L;

    private static volatile String uuid;
    private static volatile long expiresAt;
    private static volatile boolean inHand;
    /** How long the automatic gaze runs before a card action executes (0 disables it). */
    private static volatile int delayMs = 2500;

    private Inspector() {
    }

    public static void setDelayMs(int value) {
        delayMs = Math.max(0, Math.min(3000, value));
    }

    public static int delayMs() {
        return delayMs;
    }

    public static synchronized JsonObject request(String cardUuid, int durationMs) {
        JsonObject out = new JsonObject();
        if (AbstractDungeon.player == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "NOT_READY");
            return out;
        }
        AbstractCard card = find(cardUuid);
        if (card == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "CARD_NOT_FOUND");
            return out;
        }
        long ms = Math.max(200L, Math.min(MAX_DURATION_MS, durationMs <= 0 ? 4000 : durationMs));
        uuid = cardUuid;
        inHand = AbstractDungeon.player.hand != null
                && AbstractDungeon.player.hand.group.contains(card);
        expiresAt = System.currentTimeMillis() + ms;
        out.addProperty("ok", true);
        out.addProperty("uuid", cardUuid);
        // Stable id used by notes (note_set {kind:"card", id:...}).
        out.addProperty("id", card.cardID);
        out.addProperty("name", card.name);
        out.addProperty("type", card.type == null ? "SKILL" : card.type.name());
        out.addProperty("cost", card.cost);
        out.addProperty("rarity", card.rarity == null ? "BASIC" : card.rarity.name());
        // rawDescription still carries !D!/!B!/!M! placeholders; CardText substitutes the live
        // numbers (falling back to the base values) exactly the way the game renders the card.
        String desc = spirelink.state.CardText.describe(card);
        out.addProperty("raw_text", card.rawDescription == null ? "" : card.rawDescription);
        // Cards that have not been through applyPowers yet can still carry tokens: fall back to the
        // base numbers so the agent never reads a raw "!D!" placeholder.
        desc = desc.replace("!D!", Integer.toString(Math.max(card.baseDamage, 0)))
                .replace("!B!", Integer.toString(Math.max(card.baseBlock, 0)))
                .replace("!M!", Integer.toString(Math.max(card.baseMagicNumber, 0)));
        out.addProperty("description", desc);
        com.google.gson.JsonArray keywords = new com.google.gson.JsonArray();
        if (card.keywords != null) {
            for (String keyword : card.keywords) {
                keywords.add(keyword);
            }
        }
        out.add("keywords", keywords);
        out.addProperty("shown", true);
        spirelink.ui.AgentHud.push("在看：" + card.name);
        return out;
    }

    /** InputHelper postfix: point the engine's cursor at the card while the gaze is alive. */
    public static void applyHover() {
        try {
            if (!active() || !inHand) {
                return;
            }
            AbstractCard card = find(uuid);
            if (card == null || card.hb == null) {
                return;
            }
            com.megacrit.cardcrawl.helpers.input.InputHelper.mX = (int) card.hb.cX;
            com.megacrit.cardcrawl.helpers.input.InputHelper.mY = (int) card.hb.cY;
        } catch (Throwable ignored) {
            // never break input handling
        }
    }

    private static boolean active() {
        String current = uuid;
        if (current == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            uuid = null;
            return false;
        }
        return true;
    }

    /** Fallback drawing for cards that are not in hand (no vanilla hover animation for those). */
    public static void render(SpriteBatch sb) {
        try {
            if (!active() || inHand || sb == null) {
                return;
            }
            AbstractCard card = find(uuid);
            if (card == null) {
                return;
            }
            float oldX = card.current_x;
            float oldY = card.current_y;
            float oldScale = card.drawScale;
            float oldAngle = card.angle;
            try {
                card.current_x = Settings.WIDTH * 0.30f;
                card.current_y = Settings.HEIGHT * 0.42f;
                card.drawScale = 1.25f;
                card.angle = 0f;
                card.render(sb);
                ArrayList<String> keywords = card.keywords == null
                        ? new ArrayList<String>() : new ArrayList<String>(card.keywords);
                TipHelper.renderTipForCard(card, sb, keywords);
            } finally {
                card.current_x = oldX;
                card.current_y = oldY;
                card.drawScale = oldScale;
                card.angle = oldAngle;
            }
            FontHelper.renderFontCentered(sb, FontHelper.cardTitleFont, "Agent 在看",
                    Settings.WIDTH * 0.30f, Settings.HEIGHT * 0.42f + 190f,
                    new Color(1f, 0.93f, 0.74f, 1f));
        } catch (Throwable ignored) {
            // drawing must never break the render loop
        }
    }

    /** Public: the upgrade preview needs the same lookup the gaze uses. */
    public static AbstractCard find(String cardUuid) {
        if (cardUuid == null || AbstractDungeon.player == null) {
            return null;
        }
        AbstractCard hit = findByUuid(AbstractDungeon.player.hand == null ? null
                : AbstractDungeon.player.hand.group, cardUuid);
        if (hit != null) {
            return hit;
        }
        hit = findByUuid(AbstractDungeon.player.drawPile == null ? null
                : AbstractDungeon.player.drawPile.group, cardUuid);
        if (hit != null) {
            return hit;
        }
        hit = findByUuid(AbstractDungeon.player.discardPile == null ? null
                : AbstractDungeon.player.discardPile.group, cardUuid);
        if (hit != null) {
            return hit;
        }
        hit = findByUuid(AbstractDungeon.player.exhaustPile == null ? null
                : AbstractDungeon.player.exhaustPile.group, cardUuid);
        if (hit != null) {
            return hit;
        }
        // Cards that are not in a pile yet: card rewards, the grid screen, the boss/merchant stock.
        if (AbstractDungeon.cardRewardScreen != null
                && AbstractDungeon.cardRewardScreen.rewardGroup != null) {
            hit = findByUuid(AbstractDungeon.cardRewardScreen.rewardGroup, cardUuid);
            if (hit != null) {
                return hit;
            }
        }
        if (AbstractDungeon.gridSelectScreen != null
                && AbstractDungeon.gridSelectScreen.targetGroup != null) {
            hit = findByUuid(AbstractDungeon.gridSelectScreen.targetGroup.group, cardUuid);
            if (hit != null) {
                return hit;
            }
        }
        if (AbstractDungeon.player.masterDeck != null) {
            hit = findByUuid(AbstractDungeon.player.masterDeck.group, cardUuid);
            if (hit != null) {
                return hit;
            }
        }
        return findByUuid(AbstractDungeon.player.masterDeck == null ? null
                : AbstractDungeon.player.masterDeck.group, cardUuid);
    }

    private static AbstractCard findByUuid(java.util.List<AbstractCard> cards, String cardUuid) {
        if (cards == null) {
            return null;
        }
        for (AbstractCard card : cards) {
            if (card != null && cardUuid.equals(String.valueOf(card.uuid))) {
                return card;
            }
        }
        return null;
    }
}
