package spirelink.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.shrines.GremlinMatchGame;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * GremlinMatchGame (card-matching minigame) has no dialog buttons: it runs its own update() that flips
 * a card when card.hb.hovered && InputHelper.justClickedLeft. Dialog/reward actions never applied to it,
 * which left the event with no executable action at all.
 */
public final class MatchGame {

    private MatchGame() {
    }

    /** The live minigame instance, or null when this event is not the minigame. */
    public static GremlinMatchGame current() {
        try {
            // getCurrRoom() dereferences currMapNode and NPEs on the character-select screen.
            if (AbstractDungeon.currMapNode == null || AbstractDungeon.getCurrRoom() == null) {
                return null;
            }
            Object event = AbstractDungeon.getCurrRoom().event;
            return event instanceof GremlinMatchGame ? (GremlinMatchGame) event : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean active() {
        return current() != null;
    }

    /** Cards laid out on the board (the private cards CardGroup). */
    public static java.util.List<AbstractCard> cards() {
        java.util.List<AbstractCard> out = new java.util.ArrayList<>();
        GremlinMatchGame game = current();
        if (game == null) {
            return out;
        }
        try {
            Object group = spirelink.util.FieldKit.fieldOrNull(game, "cards");
            if (group instanceof com.megacrit.cardcrawl.cards.CardGroup) {
                out.addAll(((com.megacrit.cardcrawl.cards.CardGroup) group).group);
            }
        } catch (Throwable ignored) {
            // empty list is a valid answer
        }
        return out;
    }

    public static boolean gameDone() {
        GremlinMatchGame game = current();
        if (game == null) {
            return false;
        }
        Object done = spirelink.util.FieldKit.fieldOrNull(game, "gameDone");
        return done instanceof Boolean && (Boolean) done;
    }

    /** True when this card was already matched (its id is in the game's matchedCards list). */
    public static boolean matched(AbstractCard card) {
        GremlinMatchGame game = current();
        if (game == null || card == null) {
            return false;
        }
        try {
            Object list = spirelink.util.FieldKit.fieldOrNull(game, "matchedCards");
            if (list instanceof java.util.List) {
                for (Object id : (java.util.List<?>) list) {
                    if (id != null && id.equals(card.cardID)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** First card of the current pick, still face up waiting for its pair. */
    public static AbstractCard chosenCard() {
        GremlinMatchGame game = current();
        if (game == null) {
            return null;
        }
        Object card = spirelink.util.FieldKit.fieldOrNull(game, "chosenCard");
        return card instanceof AbstractCard ? (AbstractCard) card : null;
    }

    /** Current screen of the minigame: INTRO / RULE_EXPLANATION / PLAY / COMPLETE / CLEAN_UP. */
    public static String screen() {
        GremlinMatchGame game = current();
        if (game == null) {
            return "";
        }
        Object screen = spirelink.util.FieldKit.fieldOrNull(game, "screen");
        return screen == null ? "" : screen.toString();
    }

    /** True while the board accepts flips (the minigame is actually being played). */
    public static boolean playable() {
        return "PLAY".equals(screen());
    }

    /** Signature of the last observed minigame state, used to detect progress and stalls. */
    private static String lastSignature = "-";
    private static long lastChangeAt = System.currentTimeMillis();
    private static int lastMatched = -1;

    /** Milliseconds since the minigame last changed (flip animation stuck = value keeps growing). */
    public static long flipStalledMs() {
        return System.currentTimeMillis() - lastChangeAt;
    }

    /** Short token for the state signature: flipping/matching must bump the version. */
    public static String digest() {
        GremlinMatchGame game = current();
        if (game == null) {
            return "-";
        }
        Object matched = spirelink.util.FieldKit.fieldOrNull(game, "cardsMatched");
        Object attempts = spirelink.util.FieldKit.fieldOrNull(game, "attemptCount");
        Object flipped = spirelink.util.FieldKit.fieldOrNull(game, "cardFlipped");
        AbstractCard chosen = chosenCard();
        String digest = (matched instanceof Number ? ((Number) matched).intValue() : -1) + ":"
                + (attempts instanceof Number ? ((Number) attempts).intValue() : -1) + ":"
                + (chosen == null ? "-" : chosen.cardID) + ":"
                + Boolean.TRUE.equals(flipped) + ":" + gameDone();
        if (!digest.equals(lastSignature)) {
            int matchedNow = matched instanceof Number ? ((Number) matched).intValue() : -1;
            if (lastMatched >= 0 && matchedNow > lastMatched) {
                spirelink.util.Diag.info("match-game: flip 完成 first="
                        + (chosen == null ? "-" : chosen.cardID) + " matched=" + matchedNow
                        + " attempts="
                        + (attempts instanceof Number ? ((Number) attempts).intValue() : -1));
            }
            lastMatched = matchedNow;
            lastSignature = digest;
            lastChangeAt = System.currentTimeMillis();
        }
        return digest;
    }

    /**
     * 0.1.5: 已经翻开过的牌（uuid -> 名字）。翻牌配对本来就是记忆游戏 —— 12 张背面朝上的牌
     * 在 agent 眼里曾经是一条条写着牌名的动作，配对直接照着念。现在只有翻开过的牌才报名字，
     * 其余报「背面朝上」。
     */
    private static final java.util.Map<String, String> revealed =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** The minigame instance the map above belongs to; a new game clears it. */
    private static Object revealedFor;

    private static void syncReveal() {
        GremlinMatchGame game = current();
        if (game != revealedFor) {
            revealedFor = game;
            revealed.clear();
        }
    }

    /** Records that this card has been turned face up (the agent may now know its name). */
    public static void claimReveal(AbstractCard card) {
        if (card == null) {
            return;
        }
        syncReveal();
        revealed.put(String.valueOf(card.uuid), card.name);
    }

    /** True once this card has been flipped in the current game. */
    public static boolean isRevealed(AbstractCard card) {
        if (card == null) {
            return false;
        }
        syncReveal();
        return revealed.containsKey(String.valueOf(card.uuid));
    }

    /** The name an agent may see: the real one after a flip, "背面朝上" before. */
    public static String nameFor(AbstractCard card) {
        if (card == null) {
            return "";
        }
        return isRevealed(card) ? card.name : "背面朝上";
    }

    public static JsonObject state() {
        GremlinMatchGame game = current();
        if (game == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("screen", screen());
        out.addProperty("game_done", gameDone());
        out.addProperty("flip_stalled_ms", flipStalledMs());
        Object matched = spirelink.util.FieldKit.fieldOrNull(game, "cardsMatched");
        if (matched instanceof Number) {
            out.addProperty("matched", ((Number) matched).intValue());
        }
        Object attempts = spirelink.util.FieldKit.fieldOrNull(game, "attemptCount");
        if (attempts instanceof Number) {
            out.addProperty("attempts", ((Number) attempts).intValue());
        }
        AbstractCard chosen = chosenCard();
        boolean flipped = Boolean.TRUE.equals(
                spirelink.util.FieldKit.fieldOrNull(game, "cardFlipped"));
        out.addProperty("first_pick", chosen == null ? "" : chosen.cardID);
        out.addProperty("flipping", flipped);
        JsonArray cards = new JsonArray();
        java.util.List<AbstractCard> board = cards();
        for (int i = 0; i < board.size(); i++) {
            AbstractCard card = board.get(i);
            if (card == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            // 背面朝上的牌不报 id / 名字：翻牌配对是记忆游戏，报出来就等于翻开底牌。
            boolean open = isRevealed(card) || (chosen != null && chosen == card);
            item.addProperty("id", open ? card.cardID : "");
            item.addProperty("name", open ? card.name : "背面朝上");
            item.addProperty("matched", matched(card));
            item.addProperty("is_first_pick", chosen != null && chosen == card);
            cards.add(item);
        }
        out.add("cards", cards);
        return out;
    }

    /** Flips the card at index the same way a real click would. */
    public static boolean click(int index) {
        GremlinMatchGame game = current();
        if (game == null) {
            return false;
        }
        java.util.List<AbstractCard> board = cards();
        if (index < 0 || index >= board.size()) {
            return false;
        }
        AbstractCard card = board.get(index);
        if (card == null || card.hb == null) {
            return false;
        }
        // The click itself is injected for exactly one frame by MatchGameClickPatch; setting the
        // hover/click flags here as well fired twice per request.
        spirelink.util.Diag.info("match_game: 请求翻牌 index=" + index + " id=" + card.cardID);
        return true;
    }

    /** Leaves the minigame through the event's own exit (openMap). */
    public static boolean leave() {
        GremlinMatchGame game = current();
        if (game == null) {
            return false;
        }
        try {
            java.lang.reflect.Method method =
                    com.megacrit.cardcrawl.events.AbstractImageEvent.class.getDeclaredMethod("openMap");
            method.setAccessible(true);
            method.invoke(game);
            spirelink.util.Diag.info("match_game: 放弃小游戏并离开（openMap）");
            return true;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("match_game: openMap 失败: " + t);
            return false;
        }
    }
}
