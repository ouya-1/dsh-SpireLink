package spirelink.util;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import spirelink.SpireLinkConfig;

/**
 * Test mode (config {@code test_mode}, default off): every run starts at 999 HP so unattended long
 * runs are not cut short. Nothing else changes — damage still applies and battles are real.
 */
public final class TestMode {

    public static final int TEST_HP = 999;

    private static volatile boolean enabled;
    private static volatile float speed = 1.0f;
    private static boolean speedApplied;
    private static Object appliedFor;

    private TestMode() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void setSpeed(float value) {
        speed = value;
    }

    public static float speed() {
        return speed;
    }

    /** Shortens the vanilla action durations once (frame rate is deliberately left alone). */
    public static void applySpeed() {
        if (speedApplied || speed <= 1.0f) {
            return;
        }
        speedApplied = true;
        float beforeFast = com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FAST;
        float beforeFaster = com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FASTER;
        float beforeXFast = com.megacrit.cardcrawl.core.Settings.ACTION_DUR_XFAST;
        com.megacrit.cardcrawl.core.Settings.FAST_MODE = true;
        com.megacrit.cardcrawl.core.Settings.FAST_HAND_CONF = true;
        com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FAST = beforeFast / speed;
        com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FASTER = beforeFaster / speed;
        com.megacrit.cardcrawl.core.Settings.ACTION_DUR_XFAST = beforeXFast / speed;
        Diag.info("速度倍率=" + speed + "（ACTION_DUR_FAST " + beforeFast + " → "
                + com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FAST + "，ACTION_DUR_FASTER "
                + beforeFaster + " → " + com.megacrit.cardcrawl.core.Settings.ACTION_DUR_FASTER
                + "，ACTION_DUR_XFAST " + beforeXFast + " → "
                + com.megacrit.cardcrawl.core.Settings.ACTION_DUR_XFAST + "）");
        if (speed > 3.0f) {
            Diag.warn("速度倍率 " + speed + " > 3.0：个别动画可能出现跳帧或音效错位（测试模式可接受）");
        }
        spirelink.ui.AgentHud.push("速度 ×" + speed);
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Called once per frame on the main thread; applies to each new player exactly once. */
    public static void tick() {
        if (!enabled) {
            appliedFor = null;
            return;
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            appliedFor = null;
            return;
        }
        if (appliedFor == player) {
            return;
        }
        appliedFor = player;
        apply(player);
    }

    private static void apply(AbstractPlayer player) {
        try {
            player.maxHealth = TEST_HP;
            player.currentHealth = TEST_HP;
            syncHealthBar(player);
            Diag.warn("测试模式已开启：HP 固定 999，开局回满血");
            spirelink.ui.AgentHud.push("测试模式：HP 999");
        } catch (Throwable t) {
            Diag.warn("测试模式生效失败: " + t);
        }
    }

    private static void syncHealthBar(AbstractPlayer player) {
        try {
            // Keep the vanilla health bar in step with the fields we just changed.
            java.lang.reflect.Method m = AbstractPlayer.class.getDeclaredMethod("updateHealthBar");
            m.setAccessible(true);
            m.invoke(player);
        } catch (Throwable ignored) {
            // older builds have no such method; the HUD reads the fields directly anyway
        }
    }
}
