package spirelink.actions;

import com.megacrit.cardcrawl.shop.ShopScreen;

/** Reflective access to the merchant's stock (the lists themselves are private). */
public final class ShopAccess {

    private ShopAccess() {
    }

    public static java.util.List<?> relics(ShopScreen screen) {
        return list(screen, "relics");
    }

    public static java.util.List<?> potions(ShopScreen screen) {
        return list(screen, "potions");
    }

    private static java.util.List<?> list(ShopScreen screen, String field) {
        try {
            Object value = basemod.ReflectionHacks.getPrivate(screen, ShopScreen.class, field);
            return value instanceof java.util.List ? (java.util.List<?>) value
                    : java.util.Collections.emptyList();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }
}
