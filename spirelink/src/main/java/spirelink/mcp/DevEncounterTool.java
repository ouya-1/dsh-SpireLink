package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.MonsterHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only: start a specific encounter so the enemy matrix can be swept systematically.
 *
 * <p>Also the single implementation of "drop the player into a fight", reused by
 * {@code dev_room{type:"monster"}} so the two can never drift.</p>
 *
 * <h3>Issue #73 / #64 — plan first, apply second</h3>
 * <p>{@code MonsterHelper.getEncounter(id)} answers an <b>unknown</b> id with a placeholder group
 * containing vanilla's own {@code ApologySlime} ("抱歉史莱姆") instead of returning null. So
 * {@code dev_encounter{"NoSuchEncounterXyz"}} used to answer {@code ok:true} and start a bogus
 * fight — the exact symptom in issue #73. On top of that the successful call rewrote
 * {@code AbstractDungeon.monsterList}, i.e. the vanilla pool the next real monster room draws from
 * (issue #64's crash happens inside {@code Exordium.generateExclusions()}, which indexes that
 * list).</p>
 *
 * <p>Both are fixed here:</p>
 * <ul>
 *   <li>ids are validated against the real {@code MonsterHelper.*_ENC} constants (and the
 *       ApologySlime placeholder is rejected) <em>before</em> anything is touched — {@link #plan};</li>
 *   <li>the pool is snapshotted and put back after the dev fight is mounted, so a dev encounter
 *       leaves {@code monsterList} exactly as it found it — {@link #applyMonsterList}.</li>
 * </ul>
 */
public final class DevEncounterTool {

    private DevEncounterTool() {
    }

    // ------------------------------------------------------------ plan / apply

    /**
     * Where a plan gets its candidates from.
     *
     * <p>An interface so {@link #plan} is pure and unit-testable: the tests hand in a fake source
     * built from plain strings, the game gets {@link MonsterHelper}.</p>
     */
    interface EncounterSource {

        /** Every id the game really knows. */
        Set<String> knownIds();

        /**
         * Simple class names of the monsters vanilla would build for {@code id}.
         *
         * @return {@code null} (or an empty list) when the id does not resolve to a real encounter
         */
        List<String> monsterNames(String id);
    }

    /**
     * Vanilla's answer to an id it does not understand:
     * {@code MonsterHelper.getEncounter()} falls through to
     * {@code new MonsterGroup(new ApologySlime())} instead of returning null. That placeholder is
     * what made issue #73 look like a successful call.
     *
     * <p>Compared as a literal on purpose: reading {@code MonsterHelper.APOLOGY_SLIME_ENC} would
     * initialise a game class inside unit tests.</p>
     */
    static final String APOLOGY_SLIME_ID = "Apology Slime";

    /** Simple class name of the placeholder monster (same reason: no game class in the test path). */
    static final String APOLOGY_SLIME_CLASS = "ApologySlime";

    /**
     * The outcome of the plan phase: either a rejection, or what to mount.
     *
     * <p><b>Issue #108 · C7-12.</b> {@code ids} is a <em>candidate</em> list, not a composition: a
     * vanilla encounter is an indivisible {@code MonsterGroup} with hard-coded monster positions, so
     * {@code ids:[Jaw Worm, Cultist]} mounts the first usable id and reports the rest instead of
     * silently dropping them. The answer says which id was used ({@code encounter}) and why the others
     * were not ({@code ignored_ids} / {@code unknown_ids} / {@code notice}).</p>
     *
     * <p><b>Issue #112 · C8-09.</b> The two lists are a classification of <em>what an id is</em>, not
     * of where it sits: {@code ids:[Jaw Worm, 未知名, Cultist]} used to put the unknown id into
     * {@code ignored_ids} (only a leading unknown id reached {@code unknown_ids}), telling the agent to
     * retry an id that does not exist. {@link #unknownIds} / {@link #ignoredIds} split the tail of the
     * request the same way as its head, and every requested id lands in exactly one of the two.</p>
     */
    static final class Plan {

        /** {@code null} when the plan is valid. */
        final String errorCode;
        final String errorMessage;
        /** The id that will be fought, or {@code null} for "let vanilla roll it". */
        final String encounterId;
        final String label;
        /** Every id the caller named, after trimming (empty for the random plan). */
        final List<String> requested;
        /**
         * Ids vanilla knows but which are not fought this call, in request order (C7-12).
         *
         * <p>#112 · C8-09: only ids that really exist end up here — an unknown id belongs to
         * {@link #unknown} wherever it sits in the request.</p>
         */
        final List<String> ignored;
        /**
         * Ids vanilla does not know (or would answer with the ApologySlime placeholder), from any
         * position of the request (#112 · C8-09).
         */
        final List<String> unknown;

        private Plan(String errorCode, String errorMessage, String encounterId, String label,
                List<String> requested, List<String> ignored, List<String> unknown) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.encounterId = encounterId;
            this.label = label;
            this.requested = requested == null ? new ArrayList<String>() : requested;
            this.ignored = ignored == null ? new ArrayList<String>() : ignored;
            this.unknown = unknown == null ? new ArrayList<String>() : unknown;
        }

        static Plan reject(String code, String message) {
            return new Plan(code, message, null, null, null, null, null);
        }

        static Plan of(String encounterId, String label) {
            return of(encounterId, label, null, null, null);
        }

        static Plan of(String encounterId, String label, List<String> requested,
                List<String> ignored, List<String> unknown) {
            return new Plan(null, null, encounterId, label, requested, ignored, unknown);
        }

        boolean ok() {
            return errorCode == null;
        }

        /** True for "dev_room{type:monster}": vanilla rolls the encounter itself. */
        boolean random() {
            return encounterId == null;
        }

        /** True when the caller named more than one id and only one of them can be fought. */
        boolean hasUnusedIds() {
            return !ignored.isEmpty() || !unknown.isEmpty();
        }
    }

    /**
     * C7-12 wording: which id was mounted and what happened to the rest of the list.
     *
     * <p>Pure (plain strings), so the whole matrix — one id / several usable ids / unknown ids mixed
     * with a usable one — is unit tested without a game.</p>
     *
     * @return one sentence, or {@code null} when every requested id was used
     */
    static String idsNotice(Plan plan) {
        if (plan == null || !plan.ok() || !plan.hasUnusedIds()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("一次只开一个遭遇（dev_encounter 取第一个能用的 id，C7-12）：已用 \"")
                .append(plan.encounterId).append("\"");
        if (!plan.ignored.isEmpty()) {
            sb.append("，未使用 ").append(plan.ignored);
        }
        if (!plan.unknown.isEmpty()) {
            sb.append("，不认识 ").append(plan.unknown);
        }
        sb.append("。要打其余遭遇请再单独调用一次 dev_encounter。");
        return sb.toString();
    }

    /**
     * Pure plan phase: validate the requested ids and pick the encounter to mount.
     *
     * <p>Touches nothing: no {@code AbstractDungeon} field is read or written, which is what makes
     * "被拒 = 零副作用" checkable in a unit test.</p>
     *
     * @param ids    caller supplied ids, tried in order (may be null/empty)
     * @param source where candidates come from
     * @param random when true an empty/absent id list means "let vanilla roll the encounter"
     *               ({@code dev_room{type:"monster"}}); when false it is a {@code BAD_REQUEST}
     */
    static Plan plan(List<String> ids, EncounterSource source, boolean random) {
        List<String> wanted = new ArrayList<String>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.trim().isEmpty()) {
                    wanted.add(id.trim());
                }
            }
        }
        if (wanted.isEmpty()) {
            if (random) {
                return Plan.of(null, "random");
            }
            return Plan.reject("BAD_REQUEST", "缺少 ids（或用 {list:true} 列出全部遭遇）");
        }
        Set<String> known = source == null ? null : source.knownIds();
        List<String> unknown = new ArrayList<String>();
        for (int i = 0; i < wanted.size(); i++) {
            String id = wanted.get(i);
            if (known == null || !known.contains(id)) {
                unknown.add(id);
                continue;
            }
            List<String> names;
            try {
                names = source.monsterNames(id);
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev_encounter: 构造遭遇 " + id + " 失败: " + t);
                unknown.add(id);
                continue;
            }
            if (names == null || names.isEmpty()) {
                unknown.add(id);
                continue;
            }
            if (isPlaceholder(id, names)) {
                // Vanilla answers an id it does not understand with ApologySlime. Never mount it.
                unknown.add(id);
                continue;
            }
            // C7-12: the first usable id wins; everything after it is reported, not fought.
            // #112 · C8-09: the tail is classified exactly like the head — "vanilla does not know
            // this id" is a property of the id, not of its position in the list. The tail is only
            // checked against knownIds(): an id that is not going to be fought must not build a
            // monster group (the same "no side effects" rule a rejected plan follows, #73).
            List<String> tail = wanted.subList(i + 1, wanted.size());
            unknown.addAll(unknownIds(tail, known));
            return Plan.of(id, id, new ArrayList<String>(wanted), ignoredIds(tail, known), unknown);
        }
        return Plan.reject("ENCOUNTER_NOT_FOUND", "找不到这些遭遇: " + unknown
                + "（{list:true} 列出全部可用 id）");
    }

    /**
     * #112 · C8-09: the requested ids vanilla does not know, in request order.
     *
     * <p>The QA call from the issue was {@code ids:[Jaw Worm, 未知名, Cultist]}: the unknown id used
     * to land in {@code ignored_ids} just because it was not first, which told the agent "that one
     * exists, try it again" — the opposite of the truth.</p>
     *
     * <p>Pure: {@code known} is a plain {@link Set}, so the whole matrix (unknown first / last /
     * several / duplicates) is unit tested without a game. Nulls are skipped; the caller has already
     * trimmed and dropped blank ids.</p>
     */
    static List<String> unknownIds(List<String> ids, Set<String> known) {
        List<String> out = new ArrayList<String>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            if (known == null || !known.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * #112 · C8-09: the requested ids vanilla does know, in request order — usable in principle,
     * just not fought this call ({@code ignored_ids}).
     *
     * <p>Complement of {@link #unknownIds}: every requested id ends up in exactly one of the two
     * lists, so an agent can always tell "typo" from "not this time".</p>
     */
    static List<String> ignoredIds(List<String> ids, Set<String> known) {
        List<String> out = new ArrayList<String>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            if (known != null && known.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * True when {@code names} is vanilla's "unknown id" apology placeholder rather than the
     * encounter that was asked for.
     *
     * <p>Pure: works on simple class names, so the whole check is unit tested without a game.</p>
     */
    static boolean isPlaceholder(String id, List<String> names) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (!APOLOGY_SLIME_CLASS.equals(name)) {
                return false;
            }
        }
        // "Apology Slime" itself is a legal dev encounter — only reject it for other ids.
        return !APOLOGY_SLIME_ID.equals(id);
    }

    // ------------------------------------------------------------ tools

    /** Every encounter id the game knows, from MonsterHelper's public {@code *_ENC} constants. */
    public static JsonObject list() {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        Map<String, JsonObject> byId = new LinkedHashMap<String, JsonObject>();
        try {
            for (Field field : MonsterHelper.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                        || !field.getName().endsWith("_ENC")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof String) || ((String) value).isEmpty()) {
                    continue;
                }
                String id = (String) value;
                if (byId.containsKey(id)) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("name", id);
                item.addProperty("constant", field.getName());
                item.addProperty("act", actOf(field.getName()));
                byId.put(id, item);
            }
        } catch (Throwable t) {
            return err("NOT_READY", "读取遭遇列表失败: " + t);
        }
        JsonArray encounters = new JsonArray();
        for (JsonObject item : byId.values()) {
            encounters.add(item);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("count", encounters.size());
        out.add("encounters", encounters);
        return out;
    }

    /** Act guess from the constant name ({@code LVL_1_*}, {@code *_CITY_*}, {@code LVL_3_*}). */
    private static int actOf(String constant) {
        if (constant == null) {
            return 0;
        }
        if (constant.startsWith("LVL_1") || constant.contains("_EXORDIUM")) {
            return 1;
        }
        if (constant.startsWith("LVL_2") || constant.contains("_CITY")) {
            return 2;
        }
        if (constant.startsWith("LVL_3") || constant.contains("_BEYOND")) {
            return 3;
        }
        return 0;
    }

    /** Every {@code MonsterHelper.*_ENC} value, as one set. Pure apart from the reflection read. */
    static Set<String> knownEncounterIds() {
        Set<String> ids = new LinkedHashSet<String>();
        for (Field field : MonsterHelper.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                    || !field.getName().endsWith("_ENC")) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String && !((String) value).isEmpty()) {
                    ids.add((String) value);
                }
            } catch (Throwable ignored) {
                // a constant we cannot read is simply not offered
            }
        }
        return ids;
    }

    /** {@code dev_encounter{ids:[...]}} - one id is enough; extra ids are tried in order. */
    public static JsonObject enter(List<String> ids) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        // ---- gate: nothing below may run before every rejection is decided
        if (ids == null || ids.isEmpty() || trimmed(ids).isEmpty()) {
            return err("BAD_REQUEST", "缺少 ids（或用 {list:true} 列出全部遭遇）");
        }
        if (AbstractDungeon.currMapNode == null) {
            return err("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        String busy = spirelink.util.RoomGuard.busyReason();
        if (busy != null) {
            return err("NOT_READY", "现在不能切房间: " + busy);
        }
        // ---- plan: pure validation, no game state touched (#73)
        Plan plan = plan(trimmed(ids), runtimeSource(), false);
        if (!plan.ok()) {
            return err(plan.errorCode, plan.errorMessage);
        }
        return apply(plan);
    }

    /** {@code dev_room{type:"monster"}} - the vanilla random encounter for the current act. */
    public static JsonObject enterRandom() {
        // dev_room{type:"monster"} 走这里。以前没有这两个前置检查，结果在地图（currMapNode 为空或
        // 还挂着上一局的节点）里调用时会把 AbstractDungeon.monsterList 清空，之后真正进怪物房时
        // 游戏自己 monsterList.remove(0) → ArrayIndexOutOfBoundsException → 整个游戏崩掉
        // （2026-11-02 06:18 实测，见 spirelink-debug.log 的 CrashGuard 两行）。
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        if (AbstractDungeon.currMapNode == null) {
            return err("NOT_READY", "当前没有地图节点（先在地图上选一个节点再切房间）");
        }
        String busy = spirelink.util.RoomGuard.busyReason();
        if (busy != null) {
            return err("NOT_READY", "现在不能切房间: " + busy);
        }
        // A null group makes MonsterRoom.onPlayerEntry() roll its own encounter, exactly like a real
        // monster node - no duplicated spawn logic on our side. Routed through the same plan phase so
        // there is exactly one place that decides "what gets mounted".
        return apply(plan(null, null, true));
    }

    private static List<String> trimmed(List<String> ids) {
        List<String> out = new ArrayList<String>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id != null && !id.trim().isEmpty()) {
                out.add(id.trim());
            }
        }
        return out;
    }

    private static EncounterSource runtimeSource() {
        return new EncounterSource() {
            @Override
            public Set<String> knownIds() {
                return knownEncounterIds();
            }

            @Override
            public List<String> monsterNames(String id) {
                MonsterGroup group = MonsterHelper.getEncounter(id);
                List<String> names = new ArrayList<String>();
                if (group == null || group.monsters == null) {
                    return names;
                }
                for (AbstractMonster monster : group.monsters) {
                    names.add(monster == null ? "null" : monster.getClass().getSimpleName());
                }
                return names;
            }
        };
    }

    /**
     * Apply phase: turn an accepted plan into a mounted fight.
     *
     * <p>Only reached when {@link #plan} said yes, so nothing here can leave state behind for a
     * rejected call.</p>
     */
    private static JsonObject apply(Plan plan) {
        if (!plan.ok()) {
            return err(plan.errorCode, plan.errorMessage);
        }
        if (plan.random()) {
            // A null group makes MonsterRoom.onPlayerEntry() roll its own encounter, exactly like a
            // real monster node - no duplicated spawn logic on our side.
            return enterGroup(null, plan);
        }
        MonsterGroup group;
        try {
            group = MonsterHelper.getEncounter(plan.encounterId);
        } catch (Throwable t) {
            return err("ENCOUNTER_NOT_FOUND", "构造遭遇失败: " + plan.encounterId + "（" + t + "）");
        }
        if (group == null || group.monsters == null || group.monsters.isEmpty()) {
            return err("ENCOUNTER_NOT_FOUND", "找不到这些遭遇: " + plan.encounterId);
        }
        return enterGroup(group, plan);
    }

    // ------------------------------------------------------------ apply

    /** Shared body: mount a MonsterRoom holding {@code group} and let vanilla start the fight. */
    private static JsonObject enterGroup(MonsterGroup group, Plan plan) {
        final String label = plan == null || plan.label == null ? "random" : plan.label;
        if (group != null && (group.monsters == null || group.monsters.isEmpty())) {
            return err("NOT_READY", "遭遇是空的: " + label);
        }
        if (AbstractDungeon.currMapNode == null) {
            return err("NOT_READY", "当前没有地图节点（先在地图上选一个节点再切房间）");
        }
        final AbstractRoom previousRoom = AbstractDungeon.currMapNode.room;
        // Snapshot before the first mutation: closing the screen and swapping the room are the two
        // things a failed entry cannot undo by itself, and leaving them behind is exactly issue #45's
        // 「失败后 ui 留在上一间房」。Issue #64 adds monsterList / eliteMonsterList / bossList /
        // nextRoom to the snapshot, because a rejected call must not leave the vanilla pools changed.
        final RoomRollback rollback = RoomRollback.capture(previousRoom);
        // Issue #87: the room we leave must be torn down (screen, stale effects, campfire UI, action
        // queue) before the fight is mounted - a live campfire effect would otherwise cast the new
        // room to RestRoom one frame later. Pure plan first, same as dev_room.
        final java.util.List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, DevRoomTool.RoomFacts.capture(previousRoom));
        final MonsterRoom[] mounted = new MonsterRoom[1];
        try {
            spirelink.util.RoomGuard.noteSwitch();
            String done = DevRoomTool.runSwitch(steps, previousRoom, new DevRoomTool.RoomMount() {
                @Override
                public void swap() {
                    MonsterRoom room = new MonsterRoom();
                    // Set before onPlayerEntry(): it only generates a random group when this is null.
                    room.monsters = group;
                    mounted[0] = room;
                    AbstractDungeon.currMapNode.room = room;
                }

                @Override
                public void open() {
                    // Some game code indexes monsterList when a fight starts; a fight that was never
                    // rolled through getMonsterForRoomCreation() leaves it stale. Issue #64: seed it,
                    // then put the vanilla queue back - a dev fight must not decide what the next
                    // real room spawns.
                    ArrayList<String> poolBefore = applyMonsterList(group);
                    try {
                        mounted[0].onPlayerEntry();
                    } finally {
                        restoreMonsterList(poolBefore);
                    }
                }
            });
            spirelink.util.Diag.info("dev_encounter: 切房步骤=" + steps + " 执行=" + done);
            final MonsterRoom room = mounted[0];
            if (room == null) {
                throw new IllegalStateException("房间没挂上（切房计划没有执行 SWAP_ROOM）");
            }
            // group == null 是「让原版自己摇遭遇」的语义（dev_room{type:"monster"} 就是这条路）：
            // MonsterRoom.onPlayerEntry() 把摇出来的遭遇写进 room.monsters，外层 group 到这里还是
            // null，所以以前这个循环 100% 抛 NullPointerException（issue #45）。进场之后以内层
            // room.monsters 为准，group 只在房间什么都没生成时兜底。
            List<AbstractMonster> monstersToPrepare = spawnedMonsters(room.monsters, group);
            if (monstersToPrepare.isEmpty()) {
                // 既没摇到也没指定：在 preBattlePrep 动到手牌/能量之前就失败，
                // 这样回滚之后玩家真的还站在原地，而不是被洗了一次牌。
                throw new IllegalStateException("房间里没有怪物（onPlayerEntry 之后 room.monsters 为空）");
            }
            for (AbstractMonster monster : monstersToPrepare) {
                ensureIntent(monster);
                normalizeHp(monster);
            }
            // onPlayerEntry only builds the room + monsters; the whole *player* side of combat is
            // vanilla's room-entry sequence, which a dev teleport skips. B2-15: the old code ran
            // preBattlePrep() and then drew the hand by hand, while the room's own start block drew
            // another gameHandSize -> hand=10. See startCombatLikeVanilla().
            int startFrames = startCombatLikeVanilla();
            MonsterGroup spawned = room.monsters;
            if (spawned == null || spawned.monsters == null || spawned.monsters.isEmpty()) {
                throw new IllegalStateException("房间里没有怪物");
            }
            // 再补一次：preBattlePrep / usePreBattleAction 之后有些 Boss 的 currentHealth 又回到 1
            // （Donu/Deca 250→1、CorruptHeart 750→1，实测 2026-11-02）。
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : spawned.monsters) {
                normalizeHp(monster);
            }

            if (room.phase != AbstractRoom.RoomPhase.COMBAT) {
                room.phase = AbstractRoom.RoomPhase.COMBAT;
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("encounter", label);
            // C7-12: ids is a candidate list - say which id was used and what happened to the rest,
            // instead of dropping Jaws-of-extra-ids on the floor.
            out.add("ignored_ids", stringArray(plan == null ? null : plan.ignored));
            out.add("unknown_ids", stringArray(plan == null ? null : plan.unknown));
            String notice = idsNotice(plan);
            if (notice != null) {
                out.addProperty("notice", notice);
            }
            out.addProperty("phase", room.phase.name());
            JsonArray monsters = new JsonArray();
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : spawned.monsters) {
                if (monster == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("id", monster.id);
                item.addProperty("name", monster.name);
                item.addProperty("hp", monster.currentHealth);
                item.addProperty("max_hp", monster.maxHealth);
                monsters.add(item);
            }
            out.add("monsters", monsters);
            JsonObject readiness = combatReadiness();
            out.add("readiness", readiness);
            boolean ready = readiness.get("ready").getAsBoolean();
            if (!ready) {
                // I4: never answer ok for a fight the player cannot act in.
                spirelink.util.Diag.warn("dev_encounter: 战斗未初始化，已回滚: "
                        + rollback.restore());
                return err("NOT_READY", "战斗未初始化: " + readiness);
            }
            // Verify on a later frame: the crash these guards prevent happens in the render loop, not
            // inside this call, so the tool has to look again after the effect has run.
            com.google.gson.JsonObject check = new com.google.gson.JsonObject();
            check.addProperty("encounter", label);
            spirelink.actions.DelayedActions.schedule(400L,
                    spirelink.actions.ActionTypes.VERIFY_ENCOUNTER, check);
            spirelink.ui.AgentHud.push("调试遭遇：" + label);
            spirelink.util.Diag.info("dev_encounter: 进入 " + label + "，怪物=" + monsters
                    + " phase=" + room.phase + " 起手结算=" + startFrames + "帧 readiness="
                    + readiness);
            return out;
        } catch (Throwable t) {
            // 失败即干净回滚：房间、屏幕、monsterList 一起退回去，别把半成品房间留在界面上。
            spirelink.util.Diag.warn("dev_encounter: 进入 " + label + " 失败，已回滚: "
                    + rollback.restore() + "（" + t + "）");
            return err("NOT_READY", "进入遭遇失败: " + t);
        }
    }

    // ------------------------------------------------------------ vanilla combat start (B2-15)

    /**
     * One ordered step of "make a dev-mounted fight behave exactly like a vanilla monster room".
     *
     * <p>The hand, the piles and the energy are <b>not</b> in this list on purpose — see
     * {@link #planCombatStart}.</p>
     */
    enum CombatStartStep {

        /** {@code AbstractPlayer.preBattlePrep()}: draw pile from master deck, piles cleared, energy prepped. */
        PRE_BATTLE_PREP,

        /**
         * Put the room into {@code RoomPhase.COMBAT} and zero {@code AbstractRoom.waitTimer}: the room's
         * own start block must not fire a second time later (that is B2-15's hand=10).
         */
        ENTER_COMBAT,

        /** Queue vanilla's turn-one sequence (energy, draw {@code gameHandSize}, enable end turn). */
        QUEUE_TURN_ONE,

        /** Run the queued actions to completion, so the reply describes the state the player will see. */
        SETTLE_ACTION_QUEUE
    }

    /**
     * Pure: the steps that reproduce vanilla's room-entry combat sequence.
     *
     * <p><b>B2-15.</b> The old code called {@code preBattlePrep()} and then drew the opening hand by
     * hand ("if the hand is empty, draw gameHandSize"), because the opening hand only arrives through
     * the action queue a frame later. But the room's own start block was still armed, and it draws
     * {@code gameHandSize} cards as well — so the player ended up with {@code hand=10} (the whole
     * deck) and {@code draw=0}. That is why there is deliberately <b>no draw step</b> here: the hand
     * is vanilla's, dequeued from {@link CombatStartStep#QUEUE_TURN_ONE}
     * ({@code DrawCardAction(player, gameHandSize)}, {@code AbstractRoom.update()} offset 275-294) and
     * executed by {@link CombatStartStep#SETTLE_ACTION_QUEUE}.</p>
     *
     * @param hasPlayer   {@code AbstractDungeon.player != null}
     * @param hasMonsters the mounted room has a non-empty monster group
     */
    static List<CombatStartStep> planCombatStart(boolean hasPlayer, boolean hasMonsters) {
        List<CombatStartStep> steps = new ArrayList<CombatStartStep>();
        if (!hasPlayer || !hasMonsters) {
            return steps;
        }
        steps.add(CombatStartStep.PRE_BATTLE_PREP);
        steps.add(CombatStartStep.ENTER_COMBAT);
        steps.add(CombatStartStep.QUEUE_TURN_ONE);
        steps.add(CombatStartStep.SETTLE_ACTION_QUEUE);
        return steps;
    }

    /** Upper bound for the synchronous queue drain; ~60 frames is what a 1 s vanilla pause costs. */
    static final int MAX_SETTLE_FRAMES = 4000;

    /**
     * Applies {@link #planCombatStart}.
     *
     * <p>Every piece is copied from the shipped bytecode rather than guessed:</p>
     * <ul>
     *   <li>{@code AbstractDungeon.nextRoomTransition} offset 1165-1184: for a {@code MonsterRoom}
     *       ({@code !loadingSave}) vanilla calls {@code player.preBattlePrep()} right after
     *       {@code room.onPlayerEntry()} — this is the step a dev teleport never reached for
     *       {@code dev_room{type:"boss"}}, hence {@code hand=0}, {@code energy=0}, piles 0/0/0;</li>
     *   <li>{@code AbstractRoom.update()} offset 161-319: once the intro wait runs out the room sets
     *       {@code actionManager.turnHasEnded = true}, queues
     *       {@code GainEnergyAndEnableControlsAction(energyMaster)}, {@code applyStartOfCombatPreDrawLogic()},
     *       {@code DrawCardAction(player, gameHandSize)}, {@code EnableEndTurnButtonAction},
     *       {@code overlayMenu.showCombatPanels()} and {@code applyStartOfCombatLogic()};</li>
     *   <li>{@code GameActionManager} then drains that queue; {@code GainEnergyAndEnableControlsAction}
     *       clears {@code turnHasEnded} again so the turn-start block cannot draw a second hand.</li>
     * </ul>
     *
     * @return how many {@code GameActionManager.update()} frames were emulated (for the log)
     */
    static int startCombatLikeVanilla() {
        com.megacrit.cardcrawl.characters.AbstractPlayer player = AbstractDungeon.player;
        com.megacrit.cardcrawl.rooms.AbstractRoom room =
                AbstractDungeon.currMapNode == null ? null : AbstractDungeon.currMapNode.room;
        boolean hasMonsters = room != null && room.monsters != null
                && room.monsters.monsters != null && !room.monsters.monsters.isEmpty();
        List<CombatStartStep> steps = planCombatStart(player != null, hasMonsters);
        int frames = 0;
        for (CombatStartStep step : steps) {
            try {
                switch (step) {
                    case PRE_BATTLE_PREP:
                        player.preBattlePrep();
                        break;
                    case ENTER_COMBAT:
                        // The teleport skips the room transition, so the phase has to be set here:
                        // GameActionManager.update() only parks in WAITING_ON_USER (and only lets the
                        // hand layout refresh) while the current room is in COMBAT.
                        room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
                        // AbstractRoom.update() runs its own start block only when the timer was still
                        // positive on entry; zeroing it keeps vanilla from drawing a second hand a
                        // second later (that is B2-15's hand=10).
                        com.megacrit.cardcrawl.rooms.AbstractRoom.waitTimer = 0f;
                        break;
                    case QUEUE_TURN_ONE:
                        queueTurnOne(player);
                        break;
                    case SETTLE_ACTION_QUEUE:
                        frames = settleActionQueue();
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 开战步骤 " + step + " 失败: " + t);
            }
        }
        spirelink.util.Diag.info("dev: 开战步骤=" + steps + " 结算=" + frames + "帧 readiness="
                + combatReadiness());
        return frames;
    }

    /** {@code dev_room{type:"boss"|"elite"}} goes through the generic room path: patch the player side too. */
    static void startCombatIfMonsterRoom(com.megacrit.cardcrawl.rooms.AbstractRoom room) {
        if (!(room instanceof com.megacrit.cardcrawl.rooms.MonsterRoom)) {
            return;
        }
        if (room.monsters != null && room.monsters.monsters != null) {
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : room.monsters.monsters) {
                ensureIntent(monster);
                normalizeHp(monster);
            }
        }
        startCombatLikeVanilla();
        if (room.monsters != null && room.monsters.monsters != null) {
            // usePreBattleAction() 之后有些 Boss 的 currentHealth 又回到 1（见 normalizeHp）。
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : room.monsters.monsters) {
                normalizeHp(monster);
            }
        }
    }

    /** Vanilla's turn-one block ({@code AbstractRoom.update()} offsets 219-319), verbatim. */
    private static void queueTurnOne(com.megacrit.cardcrawl.characters.AbstractPlayer player) {
        com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
        if (am == null) {
            return;
        }
        am.turnHasEnded = true;
        am.addToBottom(new com.megacrit.cardcrawl.actions.common.GainEnergyAndEnableControlsAction(
                player.energy == null ? 0 : player.energy.energyMaster));
        player.applyStartOfCombatPreDrawLogic();
        am.addToBottom(new com.megacrit.cardcrawl.actions.common.DrawCardAction(
                player, player.gameHandSize));
        am.addToBottom(new com.megacrit.cardcrawl.actions.common.EnableEndTurnButtonAction());
        if (AbstractDungeon.overlayMenu != null) {
            AbstractDungeon.overlayMenu.showCombatPanels();
        }
        player.applyStartOfCombatLogic();
    }

    /**
     * Runs the queue until the player can actually act.
     *
     * <p>{@code preBattlePrep()} parks a {@code WaitAction(1s)} at the head of the queue (vanilla's
     * dramatic pause before a fight). It is dropped here: it only advances on wall-clock frames, and
     * a dev teleport has no reason to burn ~60 emulated frames on it.</p>
     */
    private static int settleActionQueue() {
        com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
        com.megacrit.cardcrawl.characters.AbstractPlayer player = AbstractDungeon.player;
        if (am == null || player == null) {
            return 0;
        }
        int dropped = dropQueuedWaitActions(am);
        if (dropped > 0) {
            spirelink.util.Diag.info("dev: 起手跳过 " + dropped + " 个 WaitAction（原版的开战停顿）");
        }
        for (int i = 0; i < MAX_SETTLE_FRAMES; i++) {
            if (playerTurnSettled(am, player)) {
                return i;
            }
            am.update();
        }
        spirelink.util.Diag.warn("dev: 起手结算未在 " + MAX_SETTLE_FRAMES + " 帧内完成 readiness="
                + combatReadiness());
        return MAX_SETTLE_FRAMES;
    }

    /** True when the queue is empty and the player really is holding a hand. */
    static boolean playerTurnSettled(Object manager, Object player) {
        try {
            com.megacrit.cardcrawl.actions.GameActionManager am =
                    (com.megacrit.cardcrawl.actions.GameActionManager) manager;
            com.megacrit.cardcrawl.characters.AbstractPlayer p =
                    (com.megacrit.cardcrawl.characters.AbstractPlayer) player;
            return am.currentAction == null
                    && (am.actions == null || am.actions.isEmpty())
                    && (am.preTurnActions == null || am.preTurnActions.isEmpty())
                    && am.phase == com.megacrit.cardcrawl.actions.GameActionManager.Phase.WAITING_ON_USER
                    && p.hand != null && !p.hand.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Removes vanilla's {@code WaitAction}s from the pending queue (see {@link #settleActionQueue()}). */
    private static int dropQueuedWaitActions(
            com.megacrit.cardcrawl.actions.GameActionManager am) {
        int dropped = 0;
        try {
            if (am.actions == null) {
                return 0;
            }
            java.util.Iterator<com.megacrit.cardcrawl.actions.AbstractGameAction> it =
                    am.actions.iterator();
            while (it.hasNext()) {
                com.megacrit.cardcrawl.actions.AbstractGameAction action = it.next();
                if (action != null && "WaitAction".equals(action.getClass().getSimpleName())) {
                    it.remove();
                    dropped++;
                }
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清理 WaitAction 失败: " + t);
        }
        return dropped;
    }

    /**
     * Hand/energy/piles check shared by the tool return and the delayed verify action.
     *
     * <p>B2-15: the numbers must be the live ones — the reply used to say {@code hand:5} while the
     * room's second draw made it 10 a second later. {@code draw > 0} is no longer required either:
     * a 0-card draw pile is a legal state (you reshuffle the discard pile).</p>
     */
    public static JsonObject combatReadiness() {
        int hand = -1;
        int draw = -1;
        int discard = -1;
        int exhaust = -1;
        int energy = -1;
        int energyMax = -1;
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer player = AbstractDungeon.player;
            if (player != null) {
                hand = player.hand == null ? -1 : player.hand.size();
                draw = player.drawPile == null ? -1 : player.drawPile.size();
                discard = player.discardPile == null ? -1 : player.discardPile.size();
                exhaust = player.exhaustPile == null ? -1 : player.exhaustPile.size();
                if (player.energy != null) {
                    energy = player.energy.energy;
                    energyMax = player.energy.energyMaster;
                }
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 读取准备度失败: " + t);
        }
        return readinessJson(hand, draw, discard, exhaust, energy, energyMax);
    }

    /** Pure: the readiness payload, straight from the numbers (no derived or rounded values). */
    static JsonObject readinessJson(int hand, int draw, int discard, int exhaust, int energy,
            int energyMax) {
        JsonObject out = new JsonObject();
        out.addProperty("ready", combatReady(hand, energyMax));
        out.addProperty("hand", hand);
        out.addProperty("draw", draw);
        out.addProperty("discard", discard);
        out.addProperty("exhaust", exhaust);
        out.addProperty("energy", energy);
        out.addProperty("energy_max", energyMax);
        return out;
    }

    /** Pure: a fight is playable when the player holds cards and has an energy pool. */
    static boolean combatReady(int hand, int energyMax) {
        return hand > 0 && energyMax > 0;
    }

    /**
     * 有些 Boss/精英的构造器只把 {@code maxHealth} 设好，{@code currentHealth} 留成 1，真正的房间
     * 会由 {@code MonsterHelper} 按幕数/进阶再缩放一次；dev 直连跳过那一步，于是「3 步打完 Donu and Deca」
     * （2026-11-02 实测：Deca/Donu 1/250、CorruptHeart 1/750、3 Shapes 1/30）。这里按满血补一次。
     */
    static void normalizeHp(com.megacrit.cardcrawl.monsters.AbstractMonster monster) {
        try {
            if (monster != null && monster.maxHealth > 1 && monster.currentHealth <= 1) {
                // AbstractMonster.setHp 是 protected，直接写公开字段（不涉及进阶缩放，dev 上下文足够）。
                monster.currentHealth = monster.maxHealth;
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_encounter: 满血修正失败: " + t);
        }
    }

    /**
     * Seeds {@code AbstractDungeon.monsterList} for the upcoming {@code onPlayerEntry()} and returns
     * what was there before, so it can be put back (issue #64).
     *
     * <p>{@code group == null} 表示「让原版自己摇遭遇」，这时候绝不能动 monsterList：
     * 清空之后原版进房间会 monsterList.remove(0) 直接 IOOBE 崩游戏。</p>
     *
     * @return the previous content, or {@code null} when nothing was touched
     */
    static ArrayList<String> applyMonsterList(MonsterGroup group) {
        try {
            if (group == null || group.monsters == null || AbstractDungeon.monsterList == null) {
                return null;
            }
            ArrayList<String> before = new ArrayList<String>(AbstractDungeon.monsterList);
            AbstractDungeon.monsterList.clear();
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : group.monsters) {
                if (monster != null && monster.id != null) {
                    AbstractDungeon.monsterList.add(monster.id);
                }
            }
            return before;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_encounter: 同步 monsterList 失败: " + t);
            return null;
        }
    }

    /** Puts the vanilla monster queue back exactly as {@link #applyMonsterList} found it. */
    static void restoreMonsterList(ArrayList<String> before) {
        if (before == null || AbstractDungeon.monsterList == null) {
            return;
        }
        try {
            AbstractDungeon.monsterList.clear();
            AbstractDungeon.monsterList.addAll(before);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_encounter: 还原 monsterList 失败: " + t);
        }
    }

    /**
     * A monster without {@code move} crashes {@code AbstractMonster.createIntent()} (null deref at the
     * very first line) as soon as BattleStartEffect calls showIntent(). Not every encounter sets the
     * first move during construction, so roll it here while we can still report a clean error.
     */
    private static void ensureIntent(com.megacrit.cardcrawl.monsters.AbstractMonster monster) {
        if (monster == null) {
            return;
        }
        try {
            if (spirelink.util.FieldKit.fieldOrNull(monster, "move") == null) {
                monster.rollMove();
                spirelink.util.Diag.warn("dev_encounter: " + monster.id
                        + " 进场时没有 move，已补 rollMove()");
            }
            monster.createIntent();
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_encounter: " + (monster.id == null ? "?" : monster.id)
                    + " 生成意图失败: " + t);
        }
    }

    /**
     * The monster list the post-{@link MonsterRoom#onPlayerEntry()} pass has to walk.
     *
     * <p>{@code dev_room{type:"monster"}} deliberately passes a null group so vanilla rolls the
     * encounter itself and stores it in {@code room.monsters}; the caller's group is still null
     * afterwards, and iterating it was the NullPointerException from issue #45. From here on the
     * room is the source of truth - the requested group is only a fallback for a room that spawned
     * nothing at all (older/mocked rooms).
     */
    static List<AbstractMonster> spawnedMonsters(MonsterGroup spawnedByRoom, MonsterGroup requested) {
        return firstNonEmpty(monstersOf(spawnedByRoom), monstersOf(requested));
    }

    /** Null-safe copy of a group's monster list: never null, never the live list. */
    static List<AbstractMonster> monstersOf(MonsterGroup group) {
        List<AbstractMonster> out = new ArrayList<AbstractMonster>();
        if (group != null && group.monsters != null) {
            out.addAll(group.monsters);
        }
        return out;
    }

    /** Pure: the first non-empty list wins; an empty (never null) list when both are empty. */
    static <T> List<T> firstNonEmpty(List<T> preferred, List<T> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        return new ArrayList<T>();
    }

    /**
     * Snapshot of the dungeon state a dev teleport overwrites, so a failed entry can be undone.
     *
     * <p>Shared by {@code dev_encounter}, {@code dev_room} and {@code dev_room{type:"monster"}}:
     * all of them close the current screen and swap {@code currMapNode.room} before they can fail.
     * Restoring the room alone (what these tools used to do) left the screen closed and the map
     * faded out, so {@code get_state} answered with the <em>previous</em> room - issue #45's
     * 「失败后 ui 留在上一间房」.</p>
     *
     * <p>Issue #64: the snapshot also covers the vanilla monster/boss pools and
     * {@code AbstractDungeon.nextRoom}. A rejected call has to leave every one of them exactly as it
     * found them — the crash this issue reports happens inside
     * {@code Exordium.generateExclusions()}, which indexes {@code AbstractDungeon.monsterList}.</p>
     */
    static final class RoomRollback {

        private final AbstractRoom previousRoom;
        private final AbstractDungeon.CurrentScreen screen;
        private final AbstractDungeon.CurrentScreen previousScreen;
        private final boolean screenUp;
        private final List<String> monsterList;
        private final List<String> eliteMonsterList;
        private final List<String> bossList;
        private final com.megacrit.cardcrawl.map.MapRoomNode nextRoom;
        /** Copies of the two reward lists the switch empties (issue #108 · C7-13). */
        private final List<com.megacrit.cardcrawl.rewards.RewardItem> roomRewards;
        private final List<com.megacrit.cardcrawl.rewards.RewardItem> rewardScreenRewards;
        private final boolean rewardTime;
        private final boolean captured;

        private RoomRollback(AbstractRoom previousRoom, AbstractDungeon.CurrentScreen screen,
                AbstractDungeon.CurrentScreen previousScreen, boolean screenUp,
                List<String> monsterList, List<String> eliteMonsterList, List<String> bossList,
                com.megacrit.cardcrawl.map.MapRoomNode nextRoom,
                List<com.megacrit.cardcrawl.rewards.RewardItem> roomRewards,
                List<com.megacrit.cardcrawl.rewards.RewardItem> rewardScreenRewards,
                boolean rewardTime, boolean captured) {
            this.previousRoom = previousRoom;
            this.screen = screen;
            this.previousScreen = previousScreen;
            this.screenUp = screenUp;
            this.monsterList = monsterList;
            this.eliteMonsterList = eliteMonsterList;
            this.bossList = bossList;
            this.nextRoom = nextRoom;
            this.roomRewards = roomRewards;
            this.rewardScreenRewards = rewardScreenRewards;
            this.rewardTime = rewardTime;
            this.captured = captured;
        }

        /** Reads the fields {@code closeCurrentScreen()} / room swaps overwrite. */
        static RoomRollback capture(AbstractRoom previousRoom) {
            AbstractDungeon.CurrentScreen screen = null;
            AbstractDungeon.CurrentScreen previousScreen = null;
            boolean screenUp = false;
            List<String> monsterList = null;
            List<String> eliteMonsterList = null;
            List<String> bossList = null;
            com.megacrit.cardcrawl.map.MapRoomNode nextRoom = null;
            List<com.megacrit.cardcrawl.rewards.RewardItem> roomRewards = null;
            List<com.megacrit.cardcrawl.rewards.RewardItem> rewardScreenRewards = null;
            boolean rewardTime = false;
            boolean captured = false;
            try {
                screen = AbstractDungeon.screen;
                previousScreen = AbstractDungeon.previousScreen;
                screenUp = AbstractDungeon.isScreenUp;
                nextRoom = AbstractDungeon.nextRoom;
                captured = true;
                // C7-13: the switch empties both reward lists (DevRoomTool.dropStaleRewards); a failed
                // switch has to hand the reward screen back intact.
                if (previousRoom != null) {
                    rewardTime = previousRoom.rewardTime;
                    if (previousRoom.rewards != null) {
                        roomRewards = new ArrayList<com.megacrit.cardcrawl.rewards.RewardItem>(
                                previousRoom.rewards);
                    }
                }
                if (AbstractDungeon.combatRewardScreen != null
                        && AbstractDungeon.combatRewardScreen.rewards != null) {
                    rewardScreenRewards = new ArrayList<com.megacrit.cardcrawl.rewards.RewardItem>(
                            AbstractDungeon.combatRewardScreen.rewards);
                }
                if (AbstractDungeon.monsterList != null) {
                    monsterList = new ArrayList<String>(AbstractDungeon.monsterList);
                }
                if (AbstractDungeon.eliteMonsterList != null) {
                    eliteMonsterList = new ArrayList<String>(AbstractDungeon.eliteMonsterList);
                }
                if (AbstractDungeon.bossList != null) {
                    bossList = new ArrayList<String>(AbstractDungeon.bossList);
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 记录回滚快照失败: " + t);
            }
            return new RoomRollback(previousRoom, screen, previousScreen, screenUp, monsterList,
                    eliteMonsterList, bossList, nextRoom, roomRewards, rewardScreenRewards, rewardTime,
                    captured);
        }

        /** Puts room, screen and every pool back; returns a one-line summary for the caller's log. */
        String restore() {
            // The half-entered room queued its own battle-start effects; they would fire against the
            // restored room and kill the render loop (the crash RoomGuard exists to prevent).
            try {
                spirelink.util.RoomGuard.clearActions();
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚动作队列失败: " + t);
            }
            try {
                if (AbstractDungeon.currMapNode != null && previousRoom != null) {
                    AbstractDungeon.currMapNode.room = previousRoom;
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚房间失败: " + t);
            }
            restorePool(AbstractDungeon.monsterList, monsterList, "monsterList");
            restorePool(AbstractDungeon.eliteMonsterList, eliteMonsterList, "eliteMonsterList");
            restorePool(AbstractDungeon.bossList, bossList, "bossList");
            restoreRewards();
            try {
                if (captured) {
                    AbstractDungeon.nextRoom = nextRoom;
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚 nextRoom 失败: " + t);
            }
            restoreScreen();
            return "room=" + (previousRoom == null ? "null"
                    : previousRoom.getClass().getSimpleName())
                    + " screen=" + (captured ? String.valueOf(screen) : "?")
                    + " isScreenUp=" + screenUp
                    + " monsterList=" + (monsterList == null ? "null" : monsterList.size());
        }

        /**
         * Puts the reward screen back exactly as {@link DevRoomTool#dropStaleRewards} found it (C7-13).
         *
         * <p>Both copies are restored, and {@code rewardTime} goes back to its captured value: a call
         * that ends in a rejection must leave the agent on the reward screen it was looking at.</p>
         */
        private void restoreRewards() {
            try {
                if (previousRoom != null && roomRewards != null && previousRoom.rewards != null) {
                    previousRoom.rewards.clear();
                    previousRoom.rewards.addAll(roomRewards);
                    previousRoom.rewardTime = rewardTime;
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚房间奖励列表失败: " + t);
            }
            try {
                com.megacrit.cardcrawl.screens.CombatRewardScreen screen =
                        AbstractDungeon.combatRewardScreen;
                if (screen != null && screen.rewards != null && rewardScreenRewards != null) {
                    screen.rewards.clear();
                    screen.rewards.addAll(rewardScreenRewards);
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚奖励屏列表失败: " + t);
            }
        }

        private static void restorePool(List<String> live, List<String> snapshot, String what) {
            if (snapshot == null || live == null) {
                return;
            }
            try {
                live.clear();
                live.addAll(snapshot);
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚 " + what + " 失败: " + t);
            }
        }

        /** Puts the screen back the way {@code closeCurrentScreen()} found it. */
        private void restoreScreen() {
            if (!captured) {
                return;
            }
            try {
                if (screen != null && screen != AbstractDungeon.CurrentScreen.NONE
                        && screen != AbstractDungeon.CurrentScreen.MAP
                        && AbstractDungeon.overlayMenu != null) {
                    // closeCurrentScreen() hid the black backdrop that screen was drawn on.
                    AbstractDungeon.overlayMenu.showBlackScreen();
                }
                AbstractDungeon.screen = screen;
                AbstractDungeon.isScreenUp = screenUp;
                AbstractDungeon.previousScreen = previousScreen;
                if (screen == AbstractDungeon.CurrentScreen.MAP
                        && AbstractDungeon.dungeonMapScreen != null
                        && AbstractDungeon.dungeonMapScreen.map != null) {
                    // closeCurrentScreen() faded the map out; without this the restored ui=map
                    // would be reported while the map itself stayed invisible.
                    AbstractDungeon.dungeonMapScreen.open(false);
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 回滚屏幕失败: " + t);
            }
        }
    }

    /** {@code List<String>} → JSON array; never null, keeps the caller's order (C7-12 payload). */
    static JsonArray stringArray(List<String> values) {
        JsonArray out = new JsonArray();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
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
