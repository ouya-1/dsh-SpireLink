package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Test-only: fabricate rewards and open the reward screen.
 *
 * <p>Issue #119 · C10-04: {@code kind=key} now builds the real chest shape through
 * {@link KeyRewards} — a real relic row in {@code room.rewards} plus the key row linked in front of
 * it — so "taking the key must not swallow the relic" (#116b) is reproducible on the real machine.</p>
 *
 * <p>Issue #60: {@code kind} used to fall through to "card" for anything unknown, so
 * {@code dev_reward{"kind":"bogus"}} silently produced a card reward, while {@code key} was
 * explicitly refused even though {@code docs/MCP-返回格式.md} §12.3 lists it. The whitelist below
 * is now the single place that decides, and {@code key} is implemented (the documentation could not
 * be changed from here).</p>
 */
public final class DevRewardTool {

    /**
     * Every legal {@code kind}, in documentation order. The first entry is the documented default
     * (a missing/blank {@code kind} means "card").
     */
    static final List<String> KINDS = Collections.unmodifiableList(
            Arrays.asList("card", "gold", "relic", "potion", "key"));

    /** {@code kind} used when the argument is missing or blank. */
    static final String DEFAULT_KIND = "card";

    private DevRewardTool() {
    }

    /**
     * Maps a caller supplied {@code kind} onto the whitelist.
     *
     * <p>Pure, so the whole matrix (legal / unknown / blank / null) is unit tested without a game
     * (see {@code DevRewardToolTest}).</p>
     *
     * @return the canonical lower-case kind, or {@code null} when it is not a legal kind —
     *         <b>never</b> a silent default (issue #60)
     */
    static String normalizeKind(String kind) {
        if (kind == null || kind.trim().isEmpty()) {
            return DEFAULT_KIND;
        }
        String key = kind.trim().toLowerCase(Locale.ROOT);
        return KINDS.contains(key) ? key : null;
    }

    /** The message every unknown {@code kind} gets: the legal values, comma separated. */
    static String unknownKindMessage(String kind) {
        StringBuilder sb = new StringBuilder("kind 只支持 ");
        for (int i = 0; i < KINDS.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(KINDS.get(i));
        }
        sb.append("（收到 \"").append(kind == null ? "" : kind).append("\"）");
        return sb.toString();
    }

    public static JsonObject add(String kind) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        String what = normalizeKind(kind);
        // Issue #60: validate BEFORE anything is touched, so a rejected call is a no-op.
        if (what == null) {
            return err("BAD_REQUEST", unknownKindMessage(kind));
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || AbstractDungeon.player == null) {
            return err("NOT_READY", "还没有房间（未进地牢）");
        }
        if (room.rewards == null) {
            room.rewards = new ArrayList<RewardItem>();
        }
        // Snapshot for the rollback below: a half-added reward that never reaches the screen would
        // stack up in room.rewards and re-open on the next frame.
        final int before = room.rewards.size();
        final boolean screenWasUp = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD;
        JsonObject key = null;
        try {
            switch (what) {
                case "gold":
                    room.addGoldToRewards(50);
                    break;
                case "relic":
                    if (!addRelic(room)) {
                        return err("NOT_READY", "遗物池为空");
                    }
                    break;
                case "potion":
                    if (!addPotion(room)) {
                        return err("NOT_READY", "药水池为空");
                    }
                    break;
                case "key":
                    // Issue #119 · C10-04: the real chest shape (a real relic row in the list + the
                    // key row linked in front of it), shared with dev_relic{id:"…Key"}. The old code
                    // linked the key to a relic row that was never added, so the screen showed a lone
                    // key and #116b (taking the key must not swallow the relic) had nothing to test.
                    key = KeyRewards.fabricate(KeyRewards.Key.SAPPHIRE);
                    break;
                case "card":
                default:
                    room.addCardReward(new RewardItem(
                            com.megacrit.cardcrawl.cards.AbstractCard.CardColor.COLORLESS));
                    break;
            }
            if (key != null) {
                // KeyRewards已经造好行并开了奖励屏（失败时它自己回滚），这里只补 kind 字段。
                if (!key.has("ok") || !key.get("ok").getAsBoolean()) {
                    return key;
                }
                key.addProperty("kind", what);
                return key;
            }
            AbstractDungeon.combatRewardScreen.open();
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("kind", what);
            // Issue #120 · C11-02: report the rows the agent can actually click (the screen's copy),
            // not the room's list: vanilla's setupItemReward() appends its own CARD row to the screen,
            // so the two counts legitimately differ and only the screen count matches get_state.
            out.addProperty("rewards", RewardScreen.rows().size());
            out.addProperty("room_rewards", RewardScreen.roomRows());
            out.add("rows", RewardScreen.describeRows());
            return out;
        } catch (Throwable t) {
            // Zero side effects on failure: take the reward we just added back out.
            rollback(room, before, screenWasUp);
            return err("NOT_READY", "造奖励失败: " + t);
        }
    }

    private static boolean addRelic(AbstractRoom room) {
        AbstractRelic relic = AbstractDungeon.returnRandomRelic(AbstractRelic.RelicTier.COMMON);
        if (relic == null) {
            return false;
        }
        room.addRelicToRewards(relic);
        return true;
    }

    private static boolean addPotion(AbstractRoom room) {
        AbstractPotion potion = AbstractDungeon.returnRandomPotion(true);
        if (potion == null) {
            return false;
        }
        room.addPotionToRewards(potion);
        return true;
    }

    /** Removes everything added after {@code before} and puts the screen back. Never throws. */
    private static void rollback(AbstractRoom room, int before, boolean screenWasUp) {
        try {
            if (room != null && room.rewards != null) {
                while (room.rewards.size() > before) {
                    room.rewards.remove(room.rewards.size() - 1);
                }
            }
        } catch (Throwable ignored) {
            // best effort
        }
        if (screenWasUp) {
            return;
        }
        try {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                AbstractDungeon.closeCurrentScreen();
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    static Class<?> find(String base, String id, Class<?> parent, String... pkgs) {
        if (id.indexOf('.') > 0) {
            try {
                Class<?> c = Class.forName(id);
                return parent.isAssignableFrom(c) ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (String pkg : pkgs) {
            try {
                Class<?> c = Class.forName(base + pkg + id);
                if (parent.isAssignableFrom(c)) {
                    return c;
                }
            } catch (Throwable ignored) {
                // next package
            }
        }
        return null;
    }

    static JsonObject err(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        return out;
    }

}
