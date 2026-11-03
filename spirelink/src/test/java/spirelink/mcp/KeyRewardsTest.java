package spirelink.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #119 · C10-04: {@code dev_relic{id:"SapphireKey"}} answered {@code RELIC_NOT_FOUND}.
 *
 * <p>钥匙在原版里不是遗物：{@code com.megacrit.cardcrawl.relics} 里没有 RubyKey / EmeraldKey /
 * SapphireKey 这些类。Emerald/Sapphire 是 {@code RewardItem.RewardType}，Ruby 只是
 * {@code Settings.hasRubyKey}（营火 Recall 选项）。所以「认得出钥匙 id」这件事只能靠一张表，而
 * 「钥匙宝箱」必须按 {@code AbstractChest.open()} 的真形态造：先把<b>遗物行</b>放进
 * {@code room.rewards}，再把钥匙行插到下标 0 并与那一行互挂 {@code relicLink} —— 否则屏幕上只有一把
 * 孤零零的钥匙，#116b（领钥匙不得吞掉遗物）就没法真机验。</p>
 *
 * <p>id 表是纯函数，直接跑；「两步都要做」这条是结构性的（缺一行就是老 bug），所以在源码层面钉住
 * {@code KeyRewards} 的造行顺序与两个工具的接线。</p>
 */
public class KeyRewardsTest {

    @Test
    public void everyKeyIdQaAndTheGameUseIsRecognised() {
        for (String id : new String[] {"RubyKey", "rubykey", "Ruby", "RedKey", "Ruby Key"}) {
            assertEquals(id, KeyRewards.Key.RUBY, KeyRewards.keyOf(id));
        }
        for (String id : new String[] {"EmeraldKey", "emerald", "Green", "GREEN_KEY"}) {
            assertEquals(id, KeyRewards.Key.EMERALD, KeyRewards.keyOf(id));
        }
        for (String id : new String[] {"SapphireKey", "Sapphire", "BlueKey", "blue",
                "sapphire_key"}) {
            assertEquals(id, KeyRewards.Key.SAPPHIRE, KeyRewards.keyOf(id));
        }
    }

    @Test
    public void ordinaryRelicIdsAreNotMistakenForKeys() {
        assertNull(KeyRewards.keyOf(null));
        assertNull(KeyRewards.keyOf(""));
        assertNull(KeyRewards.keyOf("   "));
        assertNull(KeyRewards.keyOf("BurningBlood"));
        assertNull(KeyRewards.keyOf("BagOfMarbles"));
        assertNull(KeyRewards.keyOf("Key"));
        assertNull(KeyRewards.keyOf("MonkeyPaw"));
        assertNull(KeyRewards.keyOf("Ruby Key Necklace"));
    }

    @Test
    public void theErrorTextListsEveryAcceptedId() {
        String ids = KeyRewards.acceptedIds();
        for (String id : new String[] {"RubyKey", "EmeraldKey", "SapphireKey"}) {
            assertTrue(ids + " 里没有 " + id, ids.contains(id));
        }
    }

    /** Ruby has no reward row in vanilla: it must not pretend to build one. */
    @Test
    public void rubyKeyIsFlagOnly() throws IOException {
        assertEquals("RUBY_KEY", KeyRewards.Key.RUBY.code);
        assertNull("原版没有 Ruby 的 RewardType", KeyRewards.Key.RUBY.rewardType());
        assertNotNull(KeyRewards.Key.EMERALD.rewardType());
        assertNotNull(KeyRewards.Key.SAPPHIRE.rewardType());
    }

    /**
     * 真形态 = 遗物行先进表、钥匙行再插到 0 —— 缺一条就退回 #116b 的「孤零零一把钥匙」。
     */
    @Test
    public void theChestShapeAddsBothRows() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/KeyRewards.java"));
        int relicRow = code.indexOf("room.addRelicToRewards(relic)");
        int linkRow = code.indexOf("room.rewards.get(room.rewards.size() - 1)");
        int keyRow = code.indexOf("new RewardItem(relicRow,");
        int append = code.indexOf("room.rewards.add(keyRow)");
        assertTrue("先放遗物行: " + relicRow, relicRow > 0);
        assertTrue("钥匙的 link 必须是刚进表的那一行: " + linkRow, linkRow > relicRow);
        assertTrue("钥匙行要挂在这个 link 上: " + keyRow, keyRow > linkRow);
        // 原版 AbstractRoom.addSapphireKey 就是 rewards.add(new RewardItem(link, SAPPHIRE_KEY))：
        // 追加在遗物行后面，没有下标 0（javap 核过）。
        assertTrue("钥匙行要追加在遗物行后面（同 AbstractRoom.addSapphireKey）: " + append,
                append > keyRow);
        assertFalse("原版没有「插到下标 0」这回事", code.contains("room.rewards.add(0, keyRow)"));
        assertTrue("造完两行要开奖励屏，否则看不见也领不到", code.contains("combatRewardScreen.open()"));
        // 老 bug 的形状：只 new 出一条不在奖励表里的 linkItem。
        assertFalse("不许再出现「link 不在奖励表里」的老写法", code.contains("new RewardItem(link)"));
    }

    /** dev_relic 必须先问钥匙表，别把钥匙 id 丢给遗物类查找。 */
    @Test
    public void devRelicRoutesKeysBeforeLookingForARelicClass() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/DevRelicTool.java"));
        int keyCheck = code.indexOf("KeyRewards.keyOf(id)");
        int classLookup = code.indexOf("find(BASE, id, AbstractRelic.class");
        assertTrue("dev_relic 要认钥匙 id: " + keyCheck, keyCheck > 0);
        assertTrue("钥匙判断必须在遗物类查找之前（否则照样 RELIC_NOT_FOUND）: "
                + classLookup, classLookup > keyCheck);
        assertTrue("钥匙走 KeyRewards.fabricate", code.contains("KeyRewards.fabricate(key)"));
    }

    /** dev_reward{kind:"key"} 与 dev_relic 共用一个实现，别再各造一套。 */
    @Test
    public void devRewardKeySharesTheSameBuilder() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/DevRewardTool.java"));
        assertTrue("kind=key 必须复用 KeyRewards",
                code.contains("KeyRewards.fabricate(KeyRewards.Key.SAPPHIRE)"));
        assertFalse("老的私造 addKey 必须删掉", code.contains("private static void addKey("));
    }
}
