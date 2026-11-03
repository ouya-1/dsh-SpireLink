package spirelink.actions;

import com.google.gson.JsonObject;

/**
 * {@code choose_map_node} payload: {@code {"x": 3, "y": 2}}, or the Boss entry
 * {@code {"x": -1, "y": <地图行数>}} (#110).
 *
 * <p>The report: a malformed payload (x sent as a string) answered
 * {@code NOT_READY "房间还没结束（phase=…），先 proceed 离开"} — a <b>room-phase</b> sentence for a
 * <b>payload</b> problem, naming an action the same tick did not advertise (a map with reachable
 * nodes never offers {@code proceed}), and because the rejection was not parameter level it also
 * printed a false {@code planner/executor 不一致} WARN.</p>
 *
 * <p>Two things were missing and are both here now:</p>
 * <ul>
 *   <li>a type check that runs <b>before</b> any room-phase decision — the reader used to be
 *       {@code ActionExecutor.num()}, whose {@code getAsInt()} fallback is {@code -1}, which is
 *       exactly the Boss sentinel, so a typo silently became "walk to the Boss";</li>
 *   <li>a payload-level rejection shape ({@link BadRequestException} → {@code BAD_REQUEST}) for the
 *       cases that are about the request rather than about the run.</li>
 * </ul>
 *
 * <p>Pure: no game classes, the map's row count is handed in by the caller.</p>
 */
public final class MapNodeRequest {

    /**
     * The {@code x} an advertised Boss entry carries: the boss is a hitbox on the map
     * ({@code DungeonMap.bossHb}), not a {@code MapRoomNode}, so it cannot be addressed normally.
     */
    public static final int BOSS_X = -1;

    /** The map column ({@code 0..width-1}), or {@link #BOSS_X} for the Boss icon. */
    public final int x;
    /** The map row ({@code 0..rows-1}), or {@code rows} for the Boss icon. */
    public final int y;

    private MapNodeRequest(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * @throws BadRequestException when {@code x}/{@code y} are missing or not strict integers — the
     *                             caller answers {@code BAD_REQUEST} and never reaches a room-phase
     *                             message
     */
    public static MapNodeRequest parse(JsonObject payload) {
        Integer x = IntArgument.optional(payload, "x");
        Integer y = IntArgument.optional(payload, "y");
        if (x == null || y == null) {
            throw BadRequestException.badRequest(missingMessage());
        }
        return new MapNodeRequest(x.intValue(), y.intValue());
    }

    /** True when this is the advertised Boss entry ({@code x == -1}). */
    public boolean isBossEntry() {
        return x == BOSS_X;
    }

    /**
     * A negative column that is <b>not</b> the Boss sentinel ({@code x < -1}). The old code treated
     * every negative x as "the Boss", so {@code x:-5} was silently read as the Boss icon.
     */
    public boolean hasInvalidColumn() {
        return x < BOSS_X;
    }

    /** True when the row is negative: no node and no Boss entry can live there. */
    public boolean hasInvalidRow() {
        return y < 0;
    }

    /** True when the Boss entry names the Boss row ({@code y == rows}). */
    public boolean isBossRow(int rows) {
        return rows > 0 && y == rows;
    }

    /** "缺少 x/y（…）" — the payload this action expects. */
    public static String missingMessage() {
        return "缺少 x/y（payload 要写 {\"x\": <列>, \"y\": <行>}，值见 legal_actions 的"
                + " choose_map_node payload。Boss 入口是 x:-1、y:<地图行数>）";
    }

    /**
     * "payload.x=-5 不合法（…）" — a negative column other than the Boss sentinel.
     *
     * @param rows the map's row count ({@code <= 0} when it cannot be read)
     */
    public static String columnMessage(int x, int rows) {
        String boss = rows > 0
                ? "本局 Boss 入口是 {\"x\":-1,\"y\":" + rows + "}"
                : "Boss 入口是 {\"x\":-1,\"y\":<地图行数>}";
        return "payload.x=" + x + " 不合法：地图列从 0 起；Boss 入口才是负数，" + boss;
    }

    /** "payload.y=-1 不合法（…）" — rows are 0-based, the Boss row is the row count. */
    public static String rowMessage(int y, int rows) {
        String boss = rows > 0
                ? "Boss 入口的 y 是行数 " + rows
                : "Boss 入口的 y 是地图行数";
        return "payload.y=" + y + " 不合法：地图行从 0 起（" + boss + "）";
    }

    /**
     * "payload.y=3 不是 Boss 行（…）" — {@code x:-1} with a row that is not the Boss row. The old
     * reader ignored y completely on that path.
     */
    public static String bossRowMessage(int y, int rows) {
        return "payload.y=" + y + " 不是 Boss 行：Boss 入口是 {\"x\":-1,\"y\":" + rows
                + "}（地图行数），见 legal_actions 的 choose_map_node payload";
    }

    /** "choose_map_node{x:-1,y:15}" — the Boss entry this map wants, ready to paste. */
    public static String bossEntryText(int rows) {
        return ActionTypes.CHOOSE_MAP_NODE + "{x:" + BOSS_X + ",y:" + rows + "}";
    }
}
