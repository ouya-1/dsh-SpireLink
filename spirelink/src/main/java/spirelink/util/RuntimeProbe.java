package spirelink.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/** Freeze diagnostics: everything needed to tell "loop is running" from "loop is stuck". */
public final class RuntimeProbe {

    private static volatile long frames;
    private static volatile long beats;
    private static volatile long lastFrameAt = System.currentTimeMillis();
    private static volatile long lastBeatAt = System.currentTimeMillis();

    private RuntimeProbe() {
    }

    /** Called once per game frame from the PostUpdate hook. */
    public static void tick() {
        frames++;
        lastFrameAt = System.currentTimeMillis();
    }

    /**
     * Called once per main-thread pass (PreUpdate hook). Separating "the loop still runs" from
     * "a frame was rendered" matters: when the render/update loop dies the MCP thread keeps serving
     * requests, and a stalled pair of these numbers is the only honest liveness signal.
     */
    public static void beat() {
        beats++;
        lastBeatAt = System.currentTimeMillis();
    }

    public static long frames() {
        return frames;
    }

    public static long beats() {
        return beats;
    }

    public static long msSinceFrame() {
        return System.currentTimeMillis() - lastFrameAt;
    }

    public static long msSinceBeat() {
        return System.currentTimeMillis() - lastBeatAt;
    }

    public static JsonObject dump() {
        JsonObject json = new JsonObject();
        json.addProperty("frames", frames);
        try {
            json.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            json.addProperty("is_screen_up", AbstractDungeon.isScreenUp);
            json.addProperty("mode", String.valueOf(CardCrawlGame.mode));
            json.addProperty("in_run", AbstractDungeon.player != null);
            // Issue #125 · C16-01: getCurrRoom() dereferences currMapNode, which is null while a run is
            // being abandoned (mode is already CHAR_SELECT, the dungeon is gone). The NPE was swallowed
            // by the catch below, so every field after this line was lost and QA saw
            // "error: java.lang.NullPointerException" in a transition frame's diagnostic line.
            AbstractRoom room = AbstractDungeon.currMapNode == null
                    ? null : AbstractDungeon.getCurrRoom();
            json.addProperty("room", room == null ? "null" : room.getClass().getSimpleName());
            json.addProperty("room_phase", room == null ? "null" : String.valueOf(room.phase));
            GameActionManager manager = AbstractDungeon.actionManager;
            if (manager == null) {
                json.addProperty("gam_phase", "null");
            } else {
                json.addProperty("gam_phase", String.valueOf(manager.phase));
                json.addProperty("gam_actions", manager.actions == null ? -1 : manager.actions.size());
                json.addProperty("gam_turn", manager.turn);
                json.addProperty("current_action", currentAction(manager));
            }
            if (AbstractDungeon.player != null) {
                json.addProperty("end_turn_queued", AbstractDungeon.player.endTurnQueued);
                json.addProperty("player_dead", AbstractDungeon.player.isDead);
                json.addProperty("hand", AbstractDungeon.player.hand == null
                        ? -1 : AbstractDungeon.player.hand.size());
            }
            json.addProperty("end_turn_enabled", AbstractDungeon.overlayMenu != null
                    && AbstractDungeon.overlayMenu.endTurnButton != null
                    && AbstractDungeon.overlayMenu.endTurnButton.enabled);
            // Same reason: getMonsters() goes through the room as well.
            MonsterGroup monsters = room == null ? null : AbstractDungeon.getMonsters();
            if (monsters != null) {
                json.addProperty("monsters_dead", monsters.areMonstersDead());
                json.addProperty("monsters_basically_dead", monsters.areMonstersBasicallyDead());
                JsonArray list = new JsonArray();
                if (monsters.monsters != null) {
                    for (int i = 0; i < monsters.monsters.size(); i++) {
                        if (monsters.monsters.get(i) == null) {
                            continue;
                        }
                        JsonObject entry = new JsonObject();
                        entry.addProperty("i", i);
                        entry.addProperty("id", monsters.monsters.get(i).id);
                        entry.addProperty("hp", monsters.monsters.get(i).currentHealth);
                        entry.addProperty("half_dead", monsters.monsters.get(i).halfDead);
                        entry.addProperty("dying", monsters.monsters.get(i).isDying);
                        list.add(entry);
                    }
                }
                json.add("monster_list", list);
            }
            json.addProperty("effects", AbstractDungeon.effectList == null
                    ? -1 : AbstractDungeon.effectList.size());
        } catch (Throwable t) {
            json.addProperty("error", String.valueOf(t));
        }
        return json;
    }

    private static String currentAction(GameActionManager manager) {
        try {
            Object action = basemod.ReflectionHacks.getPrivate(manager, GameActionManager.class,
                    "currentAction");
            return action == null ? "null" : action.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
