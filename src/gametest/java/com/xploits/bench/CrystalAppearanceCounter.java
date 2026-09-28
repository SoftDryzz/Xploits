package com.xploits.bench;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.EndCrystalEntity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts end crystal entities added to the world (task A4 requirement 2, log only): every one seen, whoever
 * placed it. Not ownership-filtered — {@code CrystalBrain}'s own "ours" tracking is private, so this cannot
 * tell our crystals from an opponent's the way the recorder's {@code crystalsPlaced}/{@code crystalsBroken}
 * do; in a fight whose opponent never places one itself ({@code near-death}, {@code PassiveTarget}) every
 * crystal counted here is necessarily ours, which is exactly the evidence task A4 point 3 needs.
 *
 * <p>One counter for the whole session, subscribed once and never unsubscribed, the same shape as
 * {@link PlacementCounter}: a run reads it at T0 and at the close and keeps the difference.
 */
final class CrystalAppearanceCounter {
    private static final CrystalAppearanceCounter INSTANCE = new CrystalAppearanceCounter();
    private static volatile boolean subscribed;

    private final AtomicInteger appeared = new AtomicInteger();

    private CrystalAppearanceCounter() {
    }

    /** The counter, subscribed to Meteor's event bus on first use. Client thread. */
    static CrystalAppearanceCounter get() {
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
            subscribed = true;
        }
        return INSTANCE;
    }

    /** End crystals added to the world since the session began. */
    int appeared() {
        return appeared.get();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity) appeared.incrementAndGet();
    }
}
