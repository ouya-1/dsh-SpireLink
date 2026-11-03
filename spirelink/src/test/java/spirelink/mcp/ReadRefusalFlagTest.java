package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.util.ModLog;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #126 · C17-01 / C17-03 / C17-04。
 *
 * <ul>
 *   <li><b>C17-01</b>：过渡窗口里 {@code legal_actions} 带 MCP {@code isError:true}，但 body 是正常的
 *       {@code {ready:false,...}}；同刻 {@code get_state} 是正常回包 —— 读工具的「世界还没就绪」是**读成功**，
 *       不是工具失败。只有真正失败（INTERNAL / GAME_DEAD / BAD_REQUEST / act 拒绝）才留 isError。</li>
 *   <li><b>C17-03</b>：{@code get_state.shop} 补 {@code purge_cost}（与 {@code purge.available} 同源）。</li>
 *   <li><b>C17-04</b>：换幕过渡期不再刷「地图规划为空」WARN。</li>
 * </ul>
 */
public class ReadRefusalFlagTest {

    private ToolCatalog catalog;
    private MainThreadQueue queue;

    @Before
    public void setUp() {
        ModLog log = new ModLog(new File("target/read-refusal-flag-test"), false);
        StateBuilder stateBuilder = new StateBuilder(log, "test");
        queue = new MainThreadQueue();
        catalog = new ToolCatalog(queue, stateBuilder, log);
    }

    @After
    public void tearDown() {
        spirelink.ui.AgentHud.clear();
    }

    // ------------------------------------------------------------ C17-01

    private static JsonObject err(String code) {
        return new JsonParser().parse("{\"ok\":false,\"error\":{\"code\":\"" + code + "\"}}")
                .getAsJsonObject();
    }

    @Test
    public void aNotReadyReadIsNotAToolFailure() {
        assertTrue(ToolCatalog.readsRefuseInsteadOfFailing("get_state", err("NOT_READY")));
        assertTrue(ToolCatalog.readsRefuseInsteadOfFailing("legal_actions", err("NOT_READY")));
        // act 拒绝仍然是失败（agent 的动作没做成）
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("act", err("NOT_READY")));
        // 真失败照样 isError
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("get_state", err("INTERNAL")));
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("get_state", err("GAME_DEAD")));
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("legal_actions", err("BAD_REQUEST")));
        // 没有 error 块（正常回包）不走这条规则
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("get_state",
                new JsonParser().parse("{\"ok\":true}").getAsJsonObject()));
        assertFalse(ToolCatalog.readsRefuseInsteadOfFailing("get_state", null));
    }

    /** 端到端：单飞队列被占 → 读工具回 not-ready，但 MCP 层面不许是 isError。 */
    @Test
    public void legalActionsAndGetStateAnswerWithoutIsErrorWhileBusy() throws Exception {
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                queue.call(new MainThreadQueue.Task() {
                    @Override
                    public Object run() {
                        return "held";
                    }
                }, 1500L);
            }
        });
        holder.setDaemon(true);
        holder.start();
        long until = System.currentTimeMillis() + 1000L;
        while (!queue.isBusy() && System.currentTimeMillis() < until) {
            Thread.sleep(5L);
        }
        assertTrue("测试需要队列处于 BUSY", queue.isBusy());

        ToolCatalog.ToolResult legal = catalog.call("legal_actions", new JsonObject());
        assertFalse("legal_actions 的 not-ready 不许 isError", legal.isError);
        assertEquals("NOT_READY", legal.payload.getAsJsonObject("error").get("code").getAsString());
        assertFalse(legal.payload.get("ready").getAsBoolean());

        ToolCatalog.ToolResult state = catalog.call("get_state", new JsonObject());
        assertFalse("get_state 的 not-ready 不许 isError", state.isError);
        assertEquals("NOT_READY", state.payload.getAsJsonObject("error").get("code").getAsString());

        holder.join(4000L);
    }

    // ------------------------------------------------------------ C17-03

    @Test
    public void theShopCarriesThePurgePriceWhereTheActionsAre() throws java.io.IOException {
        String builder = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/state/StateBuilder.java"));
        int shop = builder.indexOf("private static JsonObject buildShop()");
        assertTrue(shop > 0);
        String body = builder.substring(shop, Math.min(builder.length(), shop + 9000));
        assertTrue("shop 顶层要有 purge_cost", body.contains("out.addProperty(\"purge_cost\""));
        assertTrue("和 purge.available 同源", body.contains(
                "out.addProperty(\"purge_available\", screen.purgeAvailable)"));
        assertTrue("价格来源只能是 ShopScreen.actualPurgeCost",
                body.contains("purge_cost\", com.megacrit.cardcrawl.shop.ShopScreen.actualPurgeCost")
                        || body.contains("out.addProperty(\"purge_cost\", com.megacrit.cardcrawl.shop.ShopScreen.actualPurgeCost)"));
    }

    // ------------------------------------------------------------ C17-04

    @Test
    public void theEmptyMapWarningIsSilentDuringAnActChange() throws java.io.IOException {
        String planner = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/actions/ActionPlanner.java"));
        int warn = planner.indexOf("地图规划为空");
        assertTrue("找不到那句 WARN", warn > 0);
        String body = planner.substring(Math.max(0, warn - 700), Math.min(planner.length(), warn + 120));
        assertTrue("换幕占位节点（y<0）不许刷 WARN: " + body, body.contains("current.y >= 0"));
        assertTrue("真实阻塞要限流", body.contains("emptyMapWarnDue()"));
        assertTrue("限流是 5 秒一条", planner.contains("now - lastEmptyMapWarnAt < 5000L"));
    }
}
