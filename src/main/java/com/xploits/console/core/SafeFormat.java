package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

import java.util.IllegalFormatException;

/**
 * The base classes' {@code String.format} (console spec §11). Meteor formats the message before
 * sending it to chat; if the template is broken, it throws and the message is lost. Here it is
 * formatted the same way (same method, same default locale) but a broken format does not swallow the
 * message: the raw template is logged, at ERROR and saying what happened.
 */
public final class SafeFormat {
    private SafeFormat() {
    }

    public record Result(String text, boolean broken) {
    }

    public static Result apply(Catalog texts, String template, Object... args) {
        try {
            return new Result(String.format(template, args), false);
        } catch (IllegalFormatException e) {
            return new Result(template + " " + texts.render(ConsoleText.BROKEN_FORMAT, "error", e.getClass().getSimpleName()), true);
        }
    }
}
