package EarthRPGItem.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Texts {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Texts() {
    }

    public static Component color(String text) {
        return LEGACY.deserialize(normalizeLegacyCodes(text));
    }

    public static Component plain(String text) {
        return Component.text(text == null ? "" : text);
    }

    public static Component item(String text) {
        return color(text).decoration(TextDecoration.ITALIC, false);
    }

    public static Component nonItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static boolean isNonItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE;
    }

    public static String plainText(Component component) {
        return PLAIN.serialize(component);
    }

    private static String normalizeLegacyCodes(String text) {
        if (text == null) return "";
        return text.replace('\u00a7', '&');
    }
}
