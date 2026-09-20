package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendLedgerTest {
    private static final Set<String> SIN_AMIGOS = Set.of();
    private static final boolean SIN_DESCONOCIDOS = false;
    private static final boolean CON_DESCONOCIDOS = true;

    @Test
    void unNombreNuevoSeAnadeYQuedaApuntadoComoNuestro() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);

        assertEquals(List.of("StormAegis44"), result.toAdd());
        assertTrue(result.toRemove().isEmpty());
        assertEquals(Set.of("StormAegis44"), ledger.added());
    }

    /** La invariante que manda sobre todas: lo que ya tenía el jugador no se toca jamás. */
    @Test
    void unAmigoQueElJugadorYaTeniaNiSeAnadeNiSeBorra() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result puesta = ledger.reconcile(Set.of("StormAegis44"), Set.of("StormAegis44"));
        assertTrue(puesta.toAdd().isEmpty(), "ya está: no hay nada que añadir");
        assertTrue(ledger.added().isEmpty(), "y sobre todo, no es nuestro");

        FriendLedger.Result soltada = ledger.release(Set.of("StormAegis44"));
        assertTrue(soltada.toRemove().isEmpty(), "no lo pusimos nosotros: no lo quitamos nosotros");
    }

    @Test
    void alSoltarSaleLoNuestroYSoloLoNuestro() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), Set.of("Dryzzical"));

        FriendLedger.Result soltada = ledger.release(Set.of("Dryzzical", "StormAegis44"));

        assertEquals(List.of("StormAegis44"), soltada.toRemove());
        assertTrue(ledger.added().isEmpty());
    }

    /**
     * Misma regla que {@code BorrowedModule}: si el jugador lo mueve a mano después que nosotros, su
     * decisión es más reciente que nuestra anotación y manda.
     */
    @Test
    void siElJugadorQuitaAManoUnoQuePusimosDejaDeSerNuestroYNoSeVuelveAPoner() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);

        // El jugador lo borra de su lista; las de origen siguen pidiéndolo.
        FriendLedger.Result tras = ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);
        assertTrue(tras.toAdd().isEmpty(), "no se le discute al jugador lo que acaba de decidir");
        assertTrue(tras.toRemove().isEmpty());
        assertTrue(ledger.added().isEmpty());

        // Y sigue sin ponerse en las reconciliaciones siguientes de esta misma activación.
        assertTrue(ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS).toAdd().isEmpty());
    }

    @Test
    void unNombreQueSaleDeLaListaDeOrigenSeQuitaDeLosAmigos() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44", "Dryzzical"), SIN_AMIGOS);

        FriendLedger.Result result =
            ledger.reconcile(Set.of("Dryzzical"), Set.of("StormAegis44", "Dryzzical"));

        assertEquals(List.of("StormAegis44"), result.toRemove());
        assertEquals(Set.of("Dryzzical"), ledger.added());
    }

    /**
     * Friends.add rechaza el nombre si get(name) != null, y get compara con equalsIgnoreCase: pedir
     * el añadido sería pedir algo que Meteor va a rechazar, y apuntarlo sería apuntar como nuestro
     * al amigo del jugador.
     */
    @Test
    void laPresenciaSeMiraSinDistinguirMayusculas() {
        FriendLedger ledger = new FriendLedger();

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), Set.of("stormaegis44"));

        assertTrue(result.toAdd().isEmpty());
        assertTrue(ledger.added().isEmpty());
    }

    /**
     * Y la propiedad va al revés: exige el nombre exacto. Si lo que hay ya no está escrito como lo
     * escribimos, no podemos demostrar que sea el nuestro, y ante la duda se suelta sin borrar nada.
     */
    @Test
    void laPropiedadExigeElNombreExacto() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);

        FriendLedger.Result result = ledger.release(Set.of("stormaegis44"));

        assertTrue(result.toRemove().isEmpty(), "no es demostrablemente el nuestro: no se borra");
        assertTrue(ledger.added().isEmpty());
    }

    @Test
    void reconciliarDosVecesSinCambiosNoHaceNada() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);

        FriendLedger.Result segunda = ledger.reconcile(Set.of("StormAegis44"), Set.of("StormAegis44"));

        assertTrue(segunda.isEmpty(), "sin cambios no se escribe nada, y cada escritura va al disco");
        assertEquals(Set.of("StormAegis44"), ledger.added());
    }

    @Test
    void unAnadidoRechazadoPorMeteorDejaDeSerNuestroYNoSeReintenta() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);

        ledger.disown("StormAegis44");
        assertTrue(ledger.added().isEmpty());

        FriendLedger.Result result = ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);
        assertTrue(result.isEmpty(), "ni se reintenta ni, sobre todo, se borra");
    }

    @Test
    void resetOlvidaLoPuestoYLoAbandonado() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS);
        ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS); // el jugador lo quitó: queda abandonado

        ledger.reset();

        assertTrue(ledger.added().isEmpty());
        assertEquals(List.of("StormAegis44"), ledger.reconcile(Set.of("StormAegis44"), SIN_AMIGOS).toAdd(),
            "una activación nueva vuelve a empezar de cero");
    }

    @Test
    void soltarSinHaberPuestoNadaNoBorraNada() {
        FriendLedger ledger = new FriendLedger();

        assertTrue(ledger.release(Set.of("StormAegis44", "Dryzzical")).isEmpty());
    }

    @Test
    void reconciliarConNulosNoRevienta() {
        FriendLedger ledger = new FriendLedger();

        assertTrue(ledger.reconcile(null, null).isEmpty());
        assertEquals(List.of("StormAegis44"), ledger.reconcile(Set.of("StormAegis44"), null).toAdd());
    }

    // --- syncable: qué se sincroniza y qué no ---------------------------------------------------

    @Test
    void syncableUneLasDosListasYRecortaLosEspacios() {
        Set<String> result = FriendLedger.syncable(
            Set.of(" StormAegis44 "), SIN_DESCONOCIDOS, Set.of("Dryzzical\t"));

        assertEquals(Set.of("StormAegis44", "Dryzzical"), result);
    }

    /**
     * La vía de ataque de una línea de chat (spec §14.2): con trust-unknown-couriers encendido,
     * cualquiera que imite un READY entra solo en known-couriers. De ahí no sale nada hacia la lista
     * de amigos de Meteor, que es global y persiste a disco.
     */
    @Test
    void syncableNoSincronizaNingunCourierConTrustUnknownCouriers() {
        Set<String> result = FriendLedger.syncable(
            Set.of("StormAegis44", "Mallory"), CON_DESCONOCIDOS, Set.of("Dryzzical"));

        assertEquals(Set.of("Dryzzical"), result, "la lista users se escribe a mano: esa sí");
    }

    @Test
    void syncableDescartaLoQueMeteorNoPodriaGuardar() {
        Set<String> result = FriendLedger.syncable(
            Arrays.asList(null, "", "   ", "Storm Aegis", "Storm\tAegis", "StormAegis44"),
            SIN_DESCONOCIDOS, Set.of());

        assertEquals(Set.of("StormAegis44"), result);
    }

    @Test
    void syncableTrataLasListasNulasComoVacias() {
        assertTrue(FriendLedger.syncable(null, SIN_DESCONOCIDOS, null).isEmpty());
        assertEquals(Set.of("Dryzzical"), FriendLedger.syncable(null, SIN_DESCONOCIDOS, Set.of("Dryzzical")));
    }

    @Test
    void unNombreEnLasDosListasSeSincronizaUnaSolaVez() {
        FriendLedger ledger = new FriendLedger();
        Set<String> wanted = FriendLedger.syncable(Set.of("Dryzzical"), SIN_DESCONOCIDOS, Set.of("Dryzzical"));

        assertEquals(List.of("Dryzzical"), ledger.reconcile(wanted, SIN_AMIGOS).toAdd());
    }

    @Test
    void apagarLaSincronizacionSueltaLoPuestoSinTocarNadaMas() {
        FriendLedger ledger = new FriendLedger();
        ledger.reconcile(Set.of("StormAegis44"), Set.of("Dryzzical"));

        // El adaptador pasa el conjunto vacío en cuanto sync-friends se apaga.
        FriendLedger.Result result = ledger.reconcile(Set.of(), Set.of("Dryzzical", "StormAegis44"));

        assertEquals(List.of("StormAegis44"), result.toRemove());
        assertFalse(result.toRemove().contains("Dryzzical"));
    }
}
