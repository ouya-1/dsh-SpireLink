package spirelink.state;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Power text cleaning + buff/debuff ordering (pure value logic). */
public class PowerTextTest {

    @Test
    public void stripsColourMarkupFromPowerText() {
        assertEquals("从卡牌获得的格挡减少 2 。",
                spirelink.util.TextClean.stripGameMarkup("从卡牌获得的 #y格挡 减少 #b2 。"));
        assertEquals("攻击伤害提升 3 。",
                spirelink.util.TextClean.stripGameMarkup("攻击伤害提升 #b3 。"));
        // NL is the game's line break; in one line it vanishes between Chinese characters.
        assertEquals("生命上限",
                spirelink.util.TextClean.stripGameMarkup("生命 NL 上限"));
    }

    @Test
    public void unknownHashTextIsKept() {
        assertEquals("#1 号", spirelink.util.TextClean.stripGameMarkup("#1 号"));
    }

    @Test
    public void buffsSortBeforeDebuffs() {
        List<PowerSerializer.PowerView> views = new ArrayList<>();
        views.add(view("Dexterity", "debuff"));
        views.add(view("Strength", "buff"));
        views.add(view("Confusion", "debuff"));
        java.util.Collections.sort(views, PowerSerializer.PowerView.BUFFS_FIRST);
        assertEquals("Strength", views.get(0).id);
        assertEquals("Confusion", views.get(1).id);
        assertEquals("Dexterity", views.get(2).id);
    }

    private static PowerSerializer.PowerView view(String id, String kind) {
        PowerSerializer.PowerView view = new PowerSerializer.PowerView();
        view.id = id;
        view.kind = kind;
        return view;
    }
}
