package com.xploits.kitrequester.core;

import com.xploits.shared.core.i18n.Msg;

/** What OrderMachine asks to be done. KitRequester runs them in order. */
public sealed interface Action {
    /** Send to the server: only "/w SnifferBuddy !kit …" or "/tpy <courier>". */
    record SendCommand(String command) implements Action {}
    /** Start emptying shulkers into the ender chest in reach. */
    record Deposit() implements Action {}
    /** Local notice. {@code alert} = toast + sound + warning; otherwise just info in local chat. */
    record Notify(Msg message, boolean alert) implements Action {}
    /** Add a courier accepted by trust to the known-couriers setting. */
    record LearnCourier(String name) implements Action {}
    /** Save progress.json. */
    record Save() implements Action {}
    /** Disable the module. */
    record Disable(String reason) implements Action {}
}
