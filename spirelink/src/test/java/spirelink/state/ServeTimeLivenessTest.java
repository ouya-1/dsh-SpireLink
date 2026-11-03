package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issues #55 / #69 on the wire: the cached body must not keep saying {@code render_crashed:false}
 * after the game died.
 *
 * <p>The bug was pure staleness, not detection: {@code meta} is built when a body is rebuilt, and
 * after the crash the main thread never rebuilds again - so every follow-up {@code get_state} served
 * the pre-crash {@code false} forever. These tests drive the serve-time writer directly with an
 * injected verdict (a live verdict needs a running game), which is exactly the production path.
 */
public class ServeTimeLivenessTest {

    private static final String CRASH = "java.lang.ArrayIndexOutOfBoundsException: -1";

    /** No game: the cold-cache shell only walks the null-player path, exactly like the real one. */
    private static StateBuilder builder() {
        return new StateBuilder(null, "test");
    }

    private static GameLiveness.Verdict dead() {
        return GameLiveness.decide(CRASH, 6_000L, GameLiveness.STALL_MS);
    }

    private static GameLiveness.Verdict alive() {
        return GameLiveness.decide(null, 0L, GameLiveness.STALL_MS);
    }

    private static GameLiveness.Verdict crashedButLooping() {
        return GameLiveness.decide(CRASH, 100L, GameLiveness.STALL_MS);
    }

    /** A pre-crash body: exactly what the cache holds when the loop dies. */
    private static JsonObject staleBody() {
        JsonObject body = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("ready", true);
        meta.addProperty("state_version", 42);
        meta.addProperty("render_crashed", false);
        meta.addProperty("ui", "map");
        body.add("meta", meta);
        JsonObject hints = new JsonObject();
        hints.addProperty("frames", 45_924L);
        body.add("hints", hints);
        body.addProperty("state_version", 42);
        return body;
    }

    // ---------------------------------------------------------------- #55: the stale false

    @Test
    public void staleFalseIsOverwrittenOnServe() {
        JsonObject source = staleBody();
        StateBuilder.Snapshot snapshot = builder().snapshotOf(source, 42, dead());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertTrue("#55: 崩溃后 render_crashed 必须变 true，不能回放崩溃前的 false",
                meta.get("render_crashed").getAsBoolean());
        assertEquals("崩溃原因要能在 meta 里读到", CRASH, meta.get("crash").getAsString());
        assertFalse("get_state 仍返回快照", snapshot.ready);
        assertEquals(42, snapshot.version);
    }

    @Test
    public void serveDoesNotMutateTheCachedBody() {
        JsonObject source = staleBody();
        builder().snapshotOf(source, 42, dead());
        assertFalse("缓存体必须原样保留，否则下一帧的 version/signature 就错了",
                source.getAsJsonObject("meta").get("render_crashed").getAsBoolean());
    }

    @Test
    public void healthyGameStaysFalse() {
        StateBuilder.Snapshot snapshot = builder().snapshotOf(staleBody(), 42, alive());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertFalse("#55: 未崩溃时该字段仍是 false（不误报）",
                meta.get("render_crashed").getAsBoolean());
        assertFalse(meta.get("game_dead").getAsBoolean());
        assertFalse("没有崩溃就不能占用 crash 字段", meta.has("crash"));
        assertFalse("没死就不能带终态提示", meta.has("dead_hint"));
        assertFalse(snapshot.gameDead);
    }

    @Test
    public void recoveredLoopKeepsRenderCrashedButClearsDeath() {
        // The three-state case from #55: captured, then the loop came back.
        StateBuilder.Snapshot snapshot = builder().snapshotOf(staleBody(), 42, crashedButLooping());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertTrue("崩溃是历史事实，粘住", meta.get("render_crashed").getAsBoolean());
        assertFalse("循环活了就不是终态", meta.get("game_dead").getAsBoolean());
        assertFalse(snapshot.gameDead);
        assertEquals(GameLiveness.REASON_CRASHED_LOOP_ALIVE,
                GameLiveness.decide(CRASH, 100L, GameLiveness.STALL_MS).reason);
    }

    // ---------------------------------------------------------------- #69: the terminal signal

    @Test
    public void deadGameCarriesTheTerminalSignal() {
        StateBuilder.Snapshot snapshot = builder().snapshotOf(staleBody(), 42, dead());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertTrue("终态信号：#69 要 agent 一眼看出这局已经死了", snapshot.gameDead);
        assertTrue(meta.get("game_dead").getAsBoolean());
        assertEquals(6_000L, meta.get("dead_for_ms").getAsLong());
        assertNotNull("要带人类可读的重启建议", meta.get("dead_hint"));
        assertTrue("建议里必须包含「重启」",
                meta.get("dead_hint").getAsString().contains("重启"));
    }

    @Test
    public void deadGameExplainsItselfInHints() {
        StateBuilder.Snapshot snapshot = builder().snapshotOf(staleBody(), 42, dead());
        JsonObject hints = snapshot.body.getAsJsonObject("hints");
        assertTrue("帧停滞要能在 hints 里读到（原来只有主线程能刷新它）",
                hints.get("frames_stalled").getAsBoolean());
        assertEquals(6_000L, hints.get("frames_stalled_ms").getAsLong());
        assertTrue(hints.get("game_dead").getAsBoolean());
    }

    @Test
    public void aStaleTerminalSignalIsRemovedWhenItRecovers() {
        JsonObject body = staleBody();
        StateBuilder.writeLiveness(body, dead());
        assertTrue(body.getAsJsonObject("meta").has("dead_hint"));
        StateBuilder.writeLiveness(body, crashedButLooping());
        JsonObject meta = body.getAsJsonObject("meta");
        assertFalse("恢复后不能留下上一次的终态提示", meta.has("dead_hint"));
        assertFalse(meta.has("dead_for_ms"));
        assertFalse(meta.get("game_dead").getAsBoolean());
        assertTrue(meta.get("render_crashed").getAsBoolean());
    }

    @Test
    public void hintsBlockIsNeverInvented() {
        JsonObject body = new JsonObject();
        body.add("meta", new JsonObject());
        StateBuilder.writeLiveness(body, dead());
        assertFalse("hints 是可裁剪块，没它就不要凭空造出来",
                body.has("hints"));
        assertTrue("但 meta 必须照写", body.getAsJsonObject("meta").get("game_dead").getAsBoolean());
    }

    @Test
    public void bodyWithoutMetaGetsOne() {
        JsonObject body = new JsonObject();
        body.addProperty("state_version", 3);
        StateBuilder.writeLiveness(body, dead());
        assertNotNull(body.get("meta"));
        assertTrue(body.getAsJsonObject("meta").get("render_crashed").getAsBoolean());
    }

    @Test
    public void nullInputsAreIgnored() {
        // The HTTP thread calls these on every refusal; a null body/verdict must be a no-op, not an
        // NPE inside the error path (an exception there would replace the honest answer).
        StateBuilder.writeLiveness(null, dead());
        StateBuilder.writeLiveness(new JsonObject(), null);
        StateBuilder.writeLivenessMeta(null, dead());
        StateBuilder.writeLivenessMeta(new JsonObject(), null);
    }

    @Test
    public void noCachedBodyStillAnswersAsDead() {
        // Cold cache (e.g. the mod crashed before the first stable frame): the shell body must still
        // carry the terminal signal, otherwise the agent sees a bare "main_menu" snapshot.
        StateBuilder.Snapshot snapshot = builder().snapshotOf(null, 7, dead());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertTrue(meta.get("render_crashed").getAsBoolean());
        assertTrue(meta.get("game_dead").getAsBoolean());
        assertEquals(7, meta.get("state_version").getAsInt());
        assertTrue(snapshot.gameDead);
        assertFalse(snapshot.ready);
    }

    @Test
    public void coldCacheAndHealthyGameStaysQuiet() {
        StateBuilder.Snapshot snapshot = builder().snapshotOf(null, 7, alive());
        JsonObject meta = snapshot.body.getAsJsonObject("meta");
        assertFalse(meta.get("render_crashed").getAsBoolean());
        assertFalse(meta.get("game_dead").getAsBoolean());
        assertFalse(snapshot.gameDead);
        assertEquals("main_menu", meta.get("ui").getAsString());
    }

    @Test
    public void metaWriterNeverLosesOtherFields() {
        JsonObject body = staleBody();
        StateBuilder.writeLiveness(body, dead());
        JsonObject meta = body.getAsJsonObject("meta");
        assertEquals("ui 不能被写丢", "map", meta.get("ui").getAsString());
        assertEquals(42, meta.get("state_version").getAsInt());
        assertNotNull("state_version 语义不变，快照仍能被校验", body.get("state_version"));
        assertNotNull("hints 仍在", body.get("hints"));
        assertEquals(45_924L, body.getAsJsonObject("hints").get("frames").getAsLong());
    }
}
