package spirelink.util;

/**
 * Strips the game's internal colour markup from text that leaves the mod.
 *
 * <p>Power descriptions arrive as {@code "从卡牌获得的 #y格挡 减少 #b2 。"} - the {@code #y}/{@code #b}
 * are colour instructions for the renderer, pure noise for a reader (human or model).
 */
public final class TextClean {

    private TextClean() {
    }

    public static String stripGameMarkup(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if ("rgbyp".indexOf(next) >= 0 || next == '#' || next == '@' || next == '.') {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        String collapsed = sb.toString().replace("NL", " ").replaceAll(" +", " ").trim();
        // Removing "#y" leaves a space in the middle of a Chinese sentence ("获得的 格挡 减少");
        // a player would read it without those gaps.
        StringBuilder out = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == ' ' && i > 0 && i + 1 < collapsed.length()
                    && isWide(collapsed.charAt(i - 1)) && isWide(collapsed.charAt(i + 1))) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** CJK / full-width punctuation: text where an inter-word space is only markup residue. */
    private static boolean isWide(char c) {
        return c >= 0x2E80;
    }
}
