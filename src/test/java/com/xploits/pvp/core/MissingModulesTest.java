package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The managed modules Meteor does not have, measured once per activation and said once. */
class MissingModulesTest {
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theMissingOnesAreTheCatalogModulesMeteorDoesNotFindInCatalogOrder() {
        Set<String> registered = Set.of("crystal-aura", "auto-trap", "auto-web", "surround", "auto-city",
            "hole-filler", "anti-anvil");
        MissingModules missing = MissingModules.measure(registered::contains);

        assertEquals(List.of("auto-anvil", "anti-bed", "anti-anchor"), missing.names());
        assertEquals(Set.of(ManagedModules.AUTO_ANVIL, ManagedModules.ANTI_BED, ManagedModules.ANTI_ANCHOR),
            missing.modules());
    }

    @Test
    void theWarningIsGivenOnceAndOnlyWithAWorld() {
        // Restored active at game start, auto-pvp is turned on before there is a world, and a chat line
        // said then is lost. The warning waits for the first tick with a world, and is said once.
        MissingModules missing = MissingModules.measure(name -> !name.equals("anti-anchor"));

        assertEquals(Optional.empty(), missing.warning(false), "no world yet: keep it");
        Optional<Msg> first = missing.warning(true);
        assertTrue(first.isPresent());
        assertEquals("Not in this Meteor build, so auto-pvp will not use: anti-anchor.", EN.render(first.get()));
        assertEquals(Optional.empty(), missing.warning(true), "once per activation");
    }

    @Test
    void withNothingMissingThereIsNothingToSay() {
        MissingModules missing = MissingModules.measure(name -> true);

        assertEquals(List.of(), missing.names());
        assertEquals(Optional.empty(), missing.warning(true));
    }

    @Test
    void aNewMeasurementIsANewActivationAndWarnsAgain() {
        MissingModules first = MissingModules.measure(name -> !name.equals("anti-anchor"));
        first.warning(true);

        assertTrue(MissingModules.measure(name -> !name.equals("anti-anchor")).warning(true).isPresent());
    }
}
