package spirelink.mcp;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #117: {@code dev_event} must never move the run's <b>progress state</b>.
 *
 * <p>现场（QA 第 9 轮 / build17）：act2 地图上 {@code dev_event{id:"BigFish"}}（{@code act} 缺省 1）
 * → 离开事件后 {@code meta.act} 变成 1、地图只剩 {@code abandon_mid_run}。根因是 swap 步骤里那行
 * {@code AbstractDungeon.actNum = act}：{@code act} 的默认值 1 是 {@code ToolCatalog#devEvent} 给的，
 * 于是「测试一个 act1 事件」把整局的幕号改写了，而地图/地板仍是原来那一幕的。</p>
 *
 * <p>#117 选择的是「只换房间，不动推进状态」（而不是拒绝跨幕调用）：事件类本来就是直接 new 出来的，
 * 从来不需要幕号。这里把这条规则钉成三层：</p>
 * <ol>
 *   <li>纯函数：{@link DevEventTool#eventAct(int, int)} 永远返回当前幕；
 *       {@link DevEventTool#actMismatchNote(int, int)} 只在不一致时报两个数字；</li>
 *   <li>源码级：{@code DevEventTool} 的<b>代码</b>（注释除外）里没有任何对
 *       act/floor/currMapNode/firstRoomChosen 的赋值，也没有重新生成地图或 new 一幕的 Dungeon；</li>
 *   <li>计划级：共用切房计划 {@link DevRoomTool.SwitchStep} 的步骤清单被钉死 —— 谁加一个
 *       「改幕/改地板/换地图」的步骤，这里先红。</li>
 * </ol>
 *
 * <p>{@code AbstractDungeon} 在无游戏的单测里初始化不了，所以第 2 层用「读源码断言」的手法
 * （与 {@code TransformUiWiringTest} / {@code ActionTypesCoverageTest} 一致）。</p>
 */
public class DevEventActLockTest {

    // ------------------------------------------------------------ pure: which act?

    @Test
    public void theEventAlwaysSitsInTheActTheRunIsIn() {
        // 需求原文：dev_event 不得改变 act。所以「请求的幕」对结果没有任何影响。
        for (int requested : new int[] {DevEventTool.ACT_UNSPECIFIED, 0, 1, 2, 3, 4}) {
            for (int current : new int[] {1, 2, 3}) {
                assertEquals("requested=" + requested + " current=" + current,
                        current, DevEventTool.eventAct(requested, current));
            }
        }
    }

    @Test
    public void theRequestedActIsOnlyReportedWhenItDiffers() {
        assertNull("不指定（缺省）时不该报不一致",
                DevEventTool.actMismatchNote(DevEventTool.ACT_UNSPECIFIED, 2));
        assertNull("一致时不该报不一致", DevEventTool.actMismatchNote(2, 2));

        String note = DevEventTool.actMismatchNote(1, 2);
        assertNotNull("跨幕必须留下一条可核对的说明", note);
        assertTrue(note, note.contains("1") && note.contains("2"));
        assertTrue("文案必须说清「不改推进状态」: " + note, note.contains("推进状态"));
        assertTrue("文案必须能追到 issue: " + note, note.contains("#117"));
    }

    // ------------------------------------------------------------ source: no progress write

    /** {@code DevEventTool} 的代码（去掉注释）里，必须没有任何「推进状态」的写入。 */
    private static final String[] FORBIDDEN_IN_DEV_EVENT = {
            "actNum =",              // 幕号（#117 的那一行）
            "floorNum =",            // 地板
            "currMapNode =",         // 当前节点（换地图/换幕才会写；换房间写的是 currMapNode.room）
            "firstRoomChosen",       // 第一间房是否已选（地图推进）
            "generateMap(",          // 重新生成地图
            "new MapRoomNode",       // 再造节点
            "new Exordium(",         // 重新构造一幕
            "new TheCity(",
            "new TheBeyond(",
            "nextDungeon(",          // 进入下一幕
    };

    @Test
    public void theToolCodeNeverWritesRunProgress() throws IOException {
        String code = stripComments(read("src/main/java/spirelink/mcp/DevEventTool.java"));
        for (String forbidden : FORBIDDEN_IN_DEV_EVENT) {
            assertFalse("dev_event 不许改推进状态（issue #117），但代码里还有: " + forbidden,
                    code.contains(forbidden));
        }
        // 正向：它仍然要换房间（只换房间是 #117 选定的方案）。
        assertTrue("dev_event 必须仍然只把当前节点的 room 换掉",
                code.contains("currMapNode.room = room"));
        // 正向：幕号只读不写（这行就是「读当前幕」的那句）。
        assertTrue(code.contains("= AbstractDungeon.actNum"));
    }

    @Test
    public void theToolDoesNotOfferItsOwnActAsADefaultAnyMore() throws IOException {
        String catalog = stripComments(read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        // 旧写法 act == null ? 1 : ... 就是 #117 的引信：缺省 1 会把 act2 的局写成 act1。
        assertFalse("dev_event 的 act 缺省值不能再是 1", catalog.contains("act == null ? 1 :"));
        assertTrue("缺省必须走「不指定」哨兵",
                catalog.contains("DevEventTool.ACT_UNSPECIFIED"));
    }

    // ------------------------------------------------------------ plan: no progress step

    @Test
    public void theSharedRoomSwitchPlanHasNoProgressStep() {
        List<String> names = new ArrayList<String>();
        for (DevRoomTool.SwitchStep step : DevRoomTool.SwitchStep.values()) {
            names.add(step.name());
        }
        assertEquals("切房计划的步骤清单是契约（#87/#89）；"
                        + "新增步骤等于给 dev_room/dev_event 开了新的写口子，先读 #117 再动",
                Arrays.asList("DROP_STALE_REWARDS", "CLOSE_SCREENS", "CLEAR_GRID_PENDING",
                        "DROP_STALE_EFFECTS", "DETACH_CAMPFIRE_UI", "CLEAR_ACTION_QUEUE",
                        "SWAP_ROOM", "OPEN_NEW_ROOM"),
                names);
    }

    // ------------------------------------------------------------ helpers

    /** 去掉块注释与行注释，只留可执行代码（注释里提到旧代码不算违规）。 */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
                continue;
            }
            out.append(source.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** Reads a repo file relative to the maven module directory (same trick as the other wiring tests). */
    static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（user.dir=" + System.getProperty("user.dir") + "）");
        return null;
    }
}
