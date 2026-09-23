package com.xploits.shared.core.i18n;

import java.util.Locale;

/**
 * A catalog key. Implemented by one enum per area, so a mistyped key does not compile.
 * {@code TravelText.NO_FIREWORKS} with area {@code travel} is {@code travel.no-fireworks}.
 */
public interface MessageKey {
    String area();

    String name();

    default String id() {
        return area() + "." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
